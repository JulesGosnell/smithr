#!/bin/bash
#
# iOS app install/uninstall sidecar (substrate-aware).
#
# SSHes into the target and installs the iOS app. The target depends on
# the substrate:
#   - simulated (default): macOS VM — uses xcrun simctl
#   - physical:            bridge container — uses pymobiledevice3
#
# The ios-phone proxy tunnels SSH from the target, so this sidecar
# doesn't know or care whether the target is local or cross-host.
#
# On SIGTERM: uninstalls the app before exiting.
#
# Env vars:
#   SMITHR_SUBSTRATE  — simulated (default) | physical
#   SSH_TARGET        — host:port for SSH (default: ios-phone:22)
#   SSH_USER          — SSH user (default: smithr for simulated, root for physical)
#   BUNDLE_ID         — iOS bundle identifier
#   APP_FILE          — path to .app/.ipa inside the container
#   API_URL           — if set, inject api-config.json into the app
#
set -e

T0=$(date +%s)
log() { echo "[ios-app] [$(( $(date +%s) - T0 ))s] $*"; }

SMITHR_SUBSTRATE="${SMITHR_SUBSTRATE:-simulated}"
SSH_TARGET="${SSH_TARGET:-ios-phone:22}"
SSH_KEY="${SSH_KEY:-}"
BUNDLE_ID="${BUNDLE_ID:-com.artha.healthcare}"
APP_FILE="${APP_FILE:-/app/Artha.app}"
REMOTE_APP_DIR="${REMOTE_APP_DIR:-/tmp/e2e-apps}"
API_URL="${API_URL:-}"
SERVER_SERVICE="${SERVER_SERVICE:-}"
SERVER_PORT="${SERVER_PORT:-3000}"

# Default SSH_USER based on substrate (simulated → macOS VM user, physical → bridge root)
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
SCP_OPTS="$COMMON_OPTS -P $SSH_PORT"

remote() {
    ssh $SSH_OPTS "$SSH_USER@$SSH_HOST" "$@"
}

APP_BASENAME=$(basename "$APP_FILE")

# Source substrate-specific functions: do_install, do_inject_config, do_launch, do_uninstall
log "Substrate: $SMITHR_SUBSTRATE"
case "$SMITHR_SUBSTRATE" in
  physical) . /opt/scripts/physical-install.sh ;;
  *)        . /opt/scripts/simulated-install.sh ;;
esac

# Teardown handler
SERVER_BRIDGE_PID=""
teardown() {
    log "Teardown starting..."
    if [ -n "$SERVER_BRIDGE_PID" ]; then
        kill "$SERVER_BRIDGE_PID" 2>/dev/null || true
        log "Server bridge stopped."
    fi
    do_uninstall
    remote "rm -rf $REMOTE_APP_DIR" 2>/dev/null || true
    log "Teardown complete."
    exit 0
}
trap teardown TERM INT

# Wait for SSH
log "Waiting for SSH at $SSH_TARGET (user: $SSH_USER)..."
for i in $(seq 1 60); do
    if remote "echo ok" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
log "SSH connected."

# Copy app to target
log "App: $APP_FILE ($(du -sh "$APP_FILE" 2>/dev/null | cut -f1))"
log "Copying app to target..."
remote "mkdir -p $REMOTE_APP_DIR"
scp $SCP_OPTS -r "$APP_FILE" "$SSH_USER@$SSH_HOST:$REMOTE_APP_DIR/"
log "App copied."

# Install
log "Installing $APP_BASENAME..."
do_install
log "Installed."

# Inject config
do_inject_config

# Server bridge (simulated only — physical uses host LAN via WiFi)
if [ "$SMITHR_SUBSTRATE" != "physical" ] && [ -n "$SERVER_SERVICE" ]; then
    log "Setting up server bridge: VM:$SERVER_PORT → $SERVER_SERVICE:$SERVER_PORT"
    ssh $SSH_OPTS \
        -R "$SERVER_PORT:$SERVER_SERVICE:$SERVER_PORT" \
        "$SSH_USER@$SSH_HOST" -N &
    SERVER_BRIDGE_PID=$!
    sleep 2
    if kill -0 "$SERVER_BRIDGE_PID" 2>/dev/null; then
        log "Server bridge ready (PID: $SERVER_BRIDGE_PID)."
    else
        log "FATAL: Server bridge SSH process died"
        exit 1
    fi
fi

# Launch
do_launch

# Signal healthy
touch /tmp/app-installed
log "Ready."

# Stay alive — sleep in background so trap fires promptly
while true; do sleep 60 & wait $!; done
