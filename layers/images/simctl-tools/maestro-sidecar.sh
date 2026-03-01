#!/bin/bash
#
# iOS Maestro sidecar — persistent container for running Maestro tests.
# Stays alive as a sidecar; run tests via:
#
#   docker exec <container> /run-test.sh <flow-file>
#
# Substrate dispatch:
#   simulated (default) — SSH into macOS VM, verify Maestro is installed there
#   physical            — SSH into bridge, set up port-forward for XCTest (22087)
#
set -e

T0=$(date +%s)
log() { echo "[ios-maestro] [$(( $(date +%s) - T0 ))s] $*"; }

SMITHR_SUBSTRATE="${SMITHR_SUBSTRATE:-simulated}"
SSH_TARGET="${SSH_TARGET:-ios-phone:22}"
SSH_KEY="${SSH_KEY:-}"

# Default SSH_USER based on substrate
if [ -z "$SSH_USER" ]; then
  case "$SMITHR_SUBSTRATE" in
    physical) SSH_USER="root" ;;
    *)        SSH_USER="smithr" ;;
  esac
fi

# Extract host:port
SSH_HOST="${SSH_TARGET%%:*}"
SSH_PORT="${SSH_TARGET##*:}"

KEY_OPT=""
if [ -n "$SSH_KEY" ] && [ -f "$SSH_KEY" ]; then
    KEY_OPT="-i $SSH_KEY"
fi

COMMON_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR $KEY_OPT"
SSH_OPTS="$COMMON_OPTS -p $SSH_PORT"

remote() {
    ssh $SSH_OPTS "$SSH_USER@$SSH_HOST" "$@"
}

# Wait for SSH
log "Substrate: $SMITHR_SUBSTRATE"
log "Waiting for SSH at $SSH_TARGET (user: $SSH_USER)..."
for i in $(seq 1 60); do
    if remote "echo ok" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
log "SSH connected."

case "$SMITHR_SUBSTRATE" in
  physical)
    # Physical: manage XCTest runner lifecycle on the bridge, then set up
    # SSH port-forward so Maestro in this sidecar can reach the XCTest HTTP
    # server via the standard driver port (7001).
    #
    # Architecture:
    #   sidecar:7001 → (SSH tunnel) → bridge:22087 → (iproxy) → device:22087
    #   Maestro connects to localhost:7001 (its default) and reaches the
    #   XCTest HTTP server running on the physical device.

    # Wait for RSD tunnel on bridge (needed for XCTest + pymobiledevice3)
    log "Waiting for RSD tunnel on bridge..."
    for i in $(seq 1 60); do
      if remote "test -f /tmp/rsd-ready" 2>/dev/null; then
        log "RSD tunnel ready on bridge"
        break
      fi
      if [ "$i" -eq 60 ]; then
        log "ERROR: RSD tunnel not ready after 120s"
        exit 1
      fi
      sleep 2
    done

    # Query device UDID from bridge.
    # SSH sessions don't inherit Docker ENV vars, so read from PID 1's environ.
    DEVICE_UDID=$(remote "tr '\0' '\n' < /proc/1/environ | grep '^SERIAL=' | cut -d= -f2" 2>/dev/null | tr -d '[:space:]')
    log "Device UDID: $DEVICE_UDID"
    echo "$DEVICE_UDID" > /tmp/device-udid

    # Start XCTest runner on bridge (if not already running)
    XCTEST_BUNDLE="${XCTEST_BUNDLE_ID:-care.artha.maestro-driver-tests}"
    if ! remote "pgrep -f 'pymobiledevice3.*xcuitest'" >/dev/null 2>&1; then
      log "Starting XCTest runner ($XCTEST_BUNDLE) on bridge..."
      remote "nohup /start-xctest.sh $XCTEST_BUNDLE </dev/null >/tmp/xctest.log 2>&1 &"
      sleep 2
    else
      log "XCTest runner already running on bridge"
    fi

    # Start iproxy on bridge (forwards device:22087 → bridge:22087)
    if ! remote "pgrep -f 'iproxy.*22087'" >/dev/null 2>&1; then
      log "Starting iproxy (device:22087 → bridge:22087)..."
      remote "nohup iproxy -u $DEVICE_UDID 22087 22087 </dev/null >/dev/null 2>&1 &"
      sleep 1
    else
      log "iproxy already running on bridge"
    fi

    # Wait for XCTest HTTP server to be reachable on bridge:22087
    log "Waiting for XCTest HTTP server on bridge:22087..."
    for i in $(seq 1 30); do
      if remote "bash -c 'exec 3<>/dev/tcp/localhost/22087 && exec 3>&-'" 2>/dev/null; then
        log "XCTest HTTP server reachable on bridge:22087"
        break
      fi
      if [ "$i" -eq 30 ]; then
        log "WARNING: XCTest HTTP server not reachable after 60s"
      fi
      sleep 2
    done

    # Set up SSH local port-forward: sidecar:7001 → bridge:22087
    # Maestro's default driver port is 7001; XCTest runner is on 22087
    log "Starting SSH tunnel: localhost:7001 → bridge:22087"
    ssh $SSH_OPTS -N -L 7001:localhost:22087 "$SSH_USER@$SSH_HOST" &
    TUNNEL_PID=$!
    sleep 1
    if kill -0 $TUNNEL_PID 2>/dev/null; then
      log "SSH tunnel established (pid $TUNNEL_PID)"
    else
      log "ERROR: SSH tunnel failed to start"
      exit 1
    fi

    # Create fake xcrun for Maestro device discovery.
    # Maestro uses xcrun simctl/devicectl (macOS-only) to find iOS devices.
    # We provide synthetic responses so Maestro sees our physical device.
    cat > /usr/local/bin/xcrun <<FAKE_XCRUN
#!/bin/bash
case "\$1" in
  simctl)
    echo '{"devicetypes":[],"runtimes":[],"devices":{},"pairs":{}}'
    ;;
  devicectl)
    shift
    OUTPUT_FILE=""
    while [ \$# -gt 0 ]; do
      case "\$1" in
        --json-output) OUTPUT_FILE="\$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    if [ -n "\$OUTPUT_FILE" ]; then
      cat > "\$OUTPUT_FILE" <<DEVICEJSON
{"result":{"devices":[{"identifier":"$DEVICE_UDID","hardwareProperties":{"udid":"$DEVICE_UDID","platform":"iOS","productType":"iPhone"},"connectionProperties":{"transportType":"wired","tunnelState":"connected"},"deviceProperties":{"name":"iPhone"}}]}}
DEVICEJSON
    fi
    ;;
esac
exit 0
FAKE_XCRUN
    chmod +x /usr/local/bin/xcrun
    log "Fake xcrun installed (UDID: $DEVICE_UDID)"

    # Pre-populate fake driver build artifacts so Maestro's
    # validateAndUpdateDriver() skips xcodebuild (which doesn't exist on Linux).
    # It checks: version.properties (must match CLI version) + *.xctestrun file.
    MAESTRO_HOME="${MAESTRO_HOME:-/opt/maestro}"
    # Extract version from jar filename (maestro-cli-2.2.0.jar) — avoids analytics noise
    MAESTRO_VER=$(ls "$MAESTRO_HOME/lib/maestro-cli-"*.jar 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    : "${MAESTRO_VER:=2.2.0}"
    DRIVER_BUILD_DIR="/root/.maestro/maestro-iphoneos-driver-build"
    mkdir -p "$DRIVER_BUILD_DIR/driver-iphoneos/Build/Products"
    echo "version=$MAESTRO_VER" > "$DRIVER_BUILD_DIR/version.properties"
    touch "$DRIVER_BUILD_DIR/driver-iphoneos/Build/Products/maestro-driver.xctestrun"
    log "Fake driver build artifacts created (version=$MAESTRO_VER)"

    # Verify Maestro distribution is available locally
    MAESTRO_HOME="${MAESTRO_HOME:-/opt/maestro}"
    if [ -x "$MAESTRO_HOME/bin/maestro" ]; then
      log "Maestro distribution found at $MAESTRO_HOME"
      if command -v java >/dev/null 2>&1; then
        JAVA_VERSION=$(java -version 2>&1 | head -1)
        log "Java: $JAVA_VERSION"
      else
        log "WARNING: Java not found — physical Maestro tests will fail"
      fi
    else
      log "WARNING: Maestro not found at $MAESTRO_HOME/bin/maestro — physical tests will fail"
    fi
    ;;

  *)
    # Simulated: verify Maestro is installed on the macOS VM
    log "Checking Maestro on VM..."
    if remote "eval \$(/usr/libexec/path_helper -s) && which maestro" >/dev/null 2>&1; then
      MAESTRO_VERSION=$(remote "eval \$(/usr/libexec/path_helper -s) && maestro --version" 2>/dev/null || echo "unknown")
      log "Maestro $MAESTRO_VERSION found on VM."
    else
      log "WARNING: Maestro not found on macOS VM. Tests will fail."
    fi
    ;;
esac

# Teardown handler — clean up XCTest runner on bridge when sidecar stops
teardown() {
  log "Teardown starting..."
  if [ "$SMITHR_SUBSTRATE" = "physical" ]; then
    remote "pkill -f 'pymobiledevice3.*xcuitest'" 2>/dev/null || true
    log "XCTest runner stopped on bridge"
  fi
  kill $(jobs -p) 2>/dev/null
  wait 2>/dev/null
  log "Teardown complete."
  exit 0
}
trap teardown TERM INT

# Signal healthy
touch /tmp/maestro-ready
log "Sidecar ready."
log "Run tests via: docker exec <container> /run-test.sh <flow-file>"

# Stay alive — if we have a tunnel PID, wait on it; otherwise sleep
if [ -n "${TUNNEL_PID:-}" ]; then
  # Tunnel dying = container exits (forces restart/investigation)
  wait $TUNNEL_PID
else
  exec sleep infinity
fi
