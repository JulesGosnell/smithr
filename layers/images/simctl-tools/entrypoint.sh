#!/bin/sh
#
# iOS app install/uninstall sidecar.
# SSHes into the macOS VM, installs the .app via xcrun simctl,
# launches the app, stays alive.
# On SIGTERM: uninstalls the app before exiting.
#
# The ios-phone proxy tunnels port 22 (SSH) from the macOS VM,
# giving this sidecar direct xcrun simctl access.
#
# Optional server connectivity:
#   API_URL=http://host:port — overwrite api-config.json in the app bundle
#                               so the app connects to the specified server
#
set -e

T0=$(date +%s)
log() { echo "[ios-app] [$(( $(date +%s) - T0 ))s] $*"; }

SSH_TARGET="${SSH_TARGET:-ios-phone:22}"
SSH_USER="${SSH_USER:-smithr}"
SSH_KEY="${SSH_KEY:-}"
BUNDLE_ID="${BUNDLE_ID:-com.artha.healthcare}"
APP_FILE="${APP_FILE:-/app/Artha.app}"
REMOTE_APP_DIR="${REMOTE_APP_DIR:-/tmp/e2e-apps}"
API_URL="${API_URL:-}"

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

teardown() {
    log "Teardown starting..."
    log "Uninstalling $BUNDLE_ID..."
    remote "xcrun simctl uninstall booted $BUNDLE_ID" 2>/dev/null || true
    remote "rm -rf $REMOTE_APP_DIR" 2>/dev/null || true
    log "Teardown complete."
    exit 0
}
trap teardown TERM INT

# Wait for SSH to be reachable
log "Waiting for SSH at $SSH_TARGET..."
for i in $(seq 1 60); do
    if remote "echo ok" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
log "SSH connected."

# Copy .app bundle to VM
log "Copying app to VM..."
remote "mkdir -p $REMOTE_APP_DIR"
scp $SCP_OPTS -r "$APP_FILE" "$SSH_USER@$SSH_HOST:$REMOTE_APP_DIR/"
log "App copied."

APP_BASENAME=$(basename "$APP_FILE")

# Install on Simulator
log "Installing $APP_BASENAME on Simulator..."
remote "xcrun simctl install booted $REMOTE_APP_DIR/$APP_BASENAME"
log "Installed."

# Inject API config if specified (must be after install, before launch)
if [ -n "$API_URL" ]; then
    log "Injecting api-config.json: $API_URL"
    DATA_CONTAINER=$(remote "xcrun simctl get_app_container booted $BUNDLE_ID data")
    DOCS_DIR="$DATA_CONTAINER/Documents"
    remote "mkdir -p $DOCS_DIR && echo '{\"apiUrl\": \"$API_URL\"}' > $DOCS_DIR/api-config.json"
    log "Config injected."
fi

# Launch
log "Launching $BUNDLE_ID..."
remote "xcrun simctl launch booted $BUNDLE_ID"
log "Launched."

# Signal healthy
touch /tmp/app-installed
log "Ready."

# Stay alive — sleep in background so trap fires promptly
while true; do sleep 60 & wait $!; done
