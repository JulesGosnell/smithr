#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
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

SIDECAR_NAME="ios-maestro"
SMITHR_SUBSTRATE="${SMITHR_SUBSTRATE:-simulated}"

. /opt/scripts/ssh-common.sh
. /opt/scripts/common-funcs.sh

LOGIN_FLOW="${LOGIN_FLOW:-}"
LOGOUT_FLOW="${LOGOUT_FLOW:-}"
BUNDLE_ID="${BUNDLE_ID:-}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"

# Maestro XCTest driver bundle IDs — defaults are Artha; override via env vars.
MAESTRO_DRIVER_BUNDLE_ID="${MAESTRO_DRIVER_BUNDLE_ID:-care.artha.maestro-driver}"
XCTEST_RUNNER_BUNDLE_ID="${XCTEST_RUNNER_BUNDLE_ID:-care.artha.maestro-driver-tests}"
XCTEST_BUNDLE="${XCTEST_BUNDLE_ID:-$XCTEST_RUNNER_BUNDLE_ID}"

# Wait for SSH
log "Substrate: $SMITHR_SUBSTRATE"
wait_for_ssh

# Teardown handler — clean up XCTest runner on bridge.
# Uninstall the runner app to guarantee the overlay is removed from the device.
# The host app (MAESTRO_DRIVER_BUNDLE_ID) stays installed to preserve developer
# certificate trust — removing ALL apps from a cert triggers iOS re-trust prompt.
# MUST be set before the case block — early exits (health check failure, install
# timeout) need teardown to run so the phone isn't left in a dirty state.
EXIT_CODE=0
teardown() {
  log "Teardown starting..."
  # Logout (best effort — app may already be gone if app sidecar tore down first)
  if [ -n "$LOGOUT_FLOW" ] && [ -f "$LOGOUT_FLOW" ]; then
    log "Running logout flow: $LOGOUT_FLOW"
    /run-test.sh "$LOGOUT_FLOW" -e APP_ID="$BUNDLE_ID" 2>&1 || true
    log "Logout complete."
  fi
  if [ "$SMITHR_SUBSTRATE" = "physical" ]; then
    RSD_ADDR=$(remote "cat /tmp/rsd-ready" 2>/dev/null)
    if [ -n "$RSD_ADDR" ]; then
      RSD_IPV6=$(echo "$RSD_ADDR" | awk '{print $1}')
      RSD_PORT=$(echo "$RSD_ADDR" | awk '{print $2}')
      # Kill device-side XCTest runner FIRST — it holds port 22087 and may
      # block uninstall if the process is still active on the device.
      log "Killing device-side XCTest runner..."
      remote "pymobiledevice3 developer dvt pkill --rsd $RSD_IPV6 $RSD_PORT --bundle $XCTEST_RUNNER_BUNDLE_ID" 2>&1 || true
      sleep 1
      # Kill bridge-side processes (pymobiledevice3 xcuitest + iproxy).
      remote "pkill -f 'pymobiledevice3.*xcuitest'" 2>/dev/null || true
      remote "pkill -f 'iproxy.*22087'" 2>/dev/null || true
      log "XCTest + iproxy stopped on bridge"
      # Uninstall runner app from device (clears overlay).
      log "Uninstalling runner app ($XCTEST_RUNNER_BUNDLE_ID)..."
      if remote "timeout 15 pymobiledevice3 apps uninstall --rsd $RSD_IPV6 $RSD_PORT $XCTEST_RUNNER_BUNDLE_ID" 2>&1; then
        log "Runner app uninstalled from device"
      else
        log "WARNING: Failed to uninstall runner app — may persist on device"
      fi
    else
      log "WARNING: No RSD address — cannot clean up device apps"
    fi
  fi
  kill $(jobs -p) 2>/dev/null
  wait 2>/dev/null
  log "Teardown complete."
  exit $EXIT_CODE
}
trap teardown TERM INT EXIT

case "$SMITHR_SUBSTRATE" in
  physical)
    # Physical: set up XCTest runner + Maestro on the bridge container.
    # Same pattern as simulated (Maestro on macOS VM) — Maestro runs where
    # the device is directly accessible:
    #
    #   bridge:22087 → (iproxy) → device:22087 → XCTest HTTP server
    #   Maestro on bridge connects to localhost:22087 directly.

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

    # Read RSD tunnel address from bridge (needed for app install + device process kill).
    RSD_ADDR=$(remote "cat /tmp/rsd-ready" 2>/dev/null)
    RSD_IPV6=$(echo "$RSD_ADDR" | awk '{print $1}')
    RSD_PORT_VAL=$(echo "$RSD_ADDR" | awk '{print $2}')
    log "RSD tunnel: [$RSD_IPV6]:$RSD_PORT_VAL"

    # Kill stale processes from previous runs before starting fresh.
    # CRITICAL: Kill the device-side XCTest runner FIRST — it holds port 22087.
    # Killing only the bridge-side pymobiledevice3 leaves the device process alive,
    # blocking port 22087 for 60+ seconds until the device-side process times out.
    # Ensure DeveloperDiskImage is mounted (required for DVT/XCTest).
    # Persists across container restarts but not device reboots.
    remote "pymobiledevice3 mounter auto-mount --rsd $RSD_IPV6 $RSD_PORT_VAL 2>&1 | grep -v 'already mounted'" || true

    remote "pymobiledevice3 developer dvt pkill --rsd $RSD_IPV6 $RSD_PORT_VAL --bundle $XCTEST_RUNNER_BUNDLE_ID" 2>/dev/null || true
    remote "pkill -f 'pymobiledevice3.*xcuitest'" 2>/dev/null || true
    remote "pkill -f 'iproxy.*22087'" 2>/dev/null || true
    sleep 1

    # Install XCTest driver apps on the physical device (via bridge).
    # The signed .app bundles are mounted at /opt/driver-apps/ on the bridge.
    # pymobiledevice3 on the bridge installs them via the RSD tunnel.
    if remote "test -d /opt/driver-apps/maestro-driver-ios.app" 2>/dev/null; then
      log "Installing XCTest driver apps on device..."

      # Install (or upgrade) driver apps — do NOT uninstall first.
      # Removing all apps from a developer cert revokes iOS trust.
      # Timeout: 60s per install to avoid hanging the build.
      timeout 60 ssh $SSH_OPTS "$SSH_USER@$SSH_HOST" \
        "pymobiledevice3 apps install --rsd $RSD_IPV6 $RSD_PORT_VAL /opt/driver-apps/maestro-driver-ios.app" \
        || { log "ERROR: Host app install timed out or failed"; exit 1; }
      log "Host app installed: $MAESTRO_DRIVER_BUNDLE_ID"
      sleep 2
      timeout 60 ssh $SSH_OPTS "$SSH_USER@$SSH_HOST" \
        "pymobiledevice3 apps install --rsd $RSD_IPV6 $RSD_PORT_VAL /opt/driver-apps/maestro-driver-iosUITests-Runner.app" \
        || { log "ERROR: Runner app install timed out or failed"; exit 1; }
      log "Runner app installed: $XCTEST_RUNNER_BUNDLE_ID"
    else
      log "WARNING: No driver apps on bridge — assuming pre-installed on device"
    fi

    # Start iproxy BEFORE XCTest runner. iproxy listens passively on bridge:22087
    # and only establishes USB forwarding when a client connects. Starting it first
    # avoids USB contention with pymobiledevice3's DVT connection during XCTest startup.
    log "Starting iproxy (device:22087 → bridge:22087)..."
    remote "nohup iproxy -u $DEVICE_UDID 22087:22087 </dev/null >/tmp/iproxy.log 2>&1 &"
    sleep 1

    log "Starting XCTest runner ($XCTEST_BUNDLE) on bridge..."
    remote "nohup /start-xctest.sh $XCTEST_BUNDLE </dev/null >/tmp/xctest.log 2>&1 &"
    sleep 2

    # Maestro connects directly to port 22087 (via MAESTRO_OPTS in run-test.sh).
    # No socat needed — iproxy forwards device:22087 → bridge:22087 directly.

    # Wait for XCTest HTTP server to respond on bridge:22087.
    # Write a health check script on the bridge — inline python through SSH
    # has quoting issues that cause silent failures.
    remote "cat > /tmp/xctest-health.py << 'HEALTHPY'
import urllib.request, sys
try:
    r = urllib.request.urlopen('http://127.0.0.1:22087/status', timeout=3)
    print(f'HTTP {r.status}', file=sys.stderr)
    sys.exit(0 if r.status == 200 else 1)
except Exception as e:
    print(f'ERR: {e}', file=sys.stderr)
    sys.exit(1)
HEALTHPY"
    log "Waiting for XCTest HTTP server on bridge:22087..."
    XCTEST_READY=false
    for i in $(seq 1 15); do
      if remote "python3 /tmp/xctest-health.py" >/dev/null 2>&1; then
        log "XCTest HTTP server responding on bridge:22087"
        XCTEST_READY=true
        break
      fi
      sleep 2
    done
    if [ "$XCTEST_READY" != "true" ]; then
      log "FATAL: XCTest HTTP server not responding after 30s"
      EXIT_CODE=1
      exit 1
    fi

    # Install fake xcrun on the bridge for Maestro device discovery.
    # Maestro uses xcrun devicectl to find physical iOS devices.
    remote "cat > /usr/local/bin/xcrun << 'FAKE_XCRUN'
#!/bin/bash
case \"\$1\" in
  simctl)
    echo '{\"devicetypes\":[],\"runtimes\":[],\"devices\":{},\"pairs\":{}}'
    ;;
  devicectl)
    shift
    OUTPUT_FILE=\"\"
    while [ \$# -gt 0 ]; do
      case \"\$1\" in
        --json-output) OUTPUT_FILE=\"\$2\"; shift 2 ;;
        *) shift ;;
      esac
    done
    if [ -n \"\$OUTPUT_FILE\" ]; then
      cat > \"\$OUTPUT_FILE\" << DEVICEJSON
{\"result\":{\"devices\":[{\"identifier\":\"$DEVICE_UDID\",\"hardwareProperties\":{\"udid\":\"$DEVICE_UDID\",\"platform\":\"iOS\",\"productType\":\"iPhone\"},\"connectionProperties\":{\"transportType\":\"wired\",\"tunnelState\":\"connected\"},\"deviceProperties\":{\"name\":\"iPhone\"}}]}}
DEVICEJSON
    fi
    ;;
esac
exit 0
FAKE_XCRUN
chmod +x /usr/local/bin/xcrun"
    log "Fake xcrun installed on bridge (UDID: $DEVICE_UDID)"

    # Pre-populate fake driver build artifacts so Maestro's
    # validateAndUpdateDriver() skips xcodebuild (doesn't exist on Linux).
    MAESTRO_VER=$(remote "ls /opt/maestro/lib/maestro-cli-*.jar 2>/dev/null" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    : "${MAESTRO_VER:=2.2.0}"
    remote "mkdir -p /root/.maestro/maestro-iphoneos-driver-build/driver-iphoneos/Build/Products && \
            echo 'version=$MAESTRO_VER' > /root/.maestro/maestro-iphoneos-driver-build/version.properties && \
            touch /root/.maestro/maestro-iphoneos-driver-build/driver-iphoneos/Build/Products/maestro-driver.xctestrun"
    log "Fake driver build artifacts created on bridge (version=$MAESTRO_VER)"

    # Verify Maestro + Java on bridge
    if remote "test -x /opt/maestro/bin/maestro" 2>/dev/null; then
      log "Maestro distribution found on bridge at /opt/maestro"
      JAVA_VERSION=$(remote "java -version 2>&1 | head -1" 2>/dev/null || echo "unknown")
      log "Java on bridge: $JAVA_VERSION"
    else
      log "WARNING: Maestro not found on bridge — physical tests will fail"
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

# Login (if flow provided)
if [ -n "$LOGIN_FLOW" ] && [ -f "$LOGIN_FLOW" ]; then
  log "Running login flow: $LOGIN_FLOW"
  if ! /run-test.sh "$LOGIN_FLOW" \
    -e EMAIL="$EMAIL" -e PASSWORD="$PASSWORD" -e APP_ID="$BUNDLE_ID"; then
    log "FATAL: Login flow failed"
    EXIT_CODE=1
    exit 1
  fi
  log "Login complete."
fi

# Signal healthy
mark_ready /tmp/maestro-ready
log "Sidecar ready. App logged in."
log "Run tests via: docker exec <container> /run-test.sh <flow-file>"

# Stay alive — background sleep + wait so the trap handler runs on SIGTERM.
# (exec sleep would replace bash and lose the trap.)
sleep infinity &
wait $!
