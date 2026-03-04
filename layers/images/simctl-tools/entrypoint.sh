#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
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
#   BUNDLE_ID         — iOS bundle identifier (default: com.artha.healthcare)
#   APP_FILE          — path to .app/.ipa inside the container
#   API_URL           — if set, inject api-config.json into the app
#
set -e

SIDECAR_NAME="ios-app"
SMITHR_SUBSTRATE="${SMITHR_SUBSTRATE:-simulated}"

. /opt/scripts/ssh-common.sh
. /opt/scripts/common-funcs.sh

# App bundle ID — default is Artha; override via BUNDLE_ID env var
BUNDLE_ID="${BUNDLE_ID:-com.artha.healthcare}"
APP_FILE="${APP_FILE:-/app/Artha.app}"
REMOTE_APP_DIR="${REMOTE_APP_DIR:-/tmp/e2e-apps}"
API_URL="${API_URL:-}"
SERVER_SERVICE="${SERVER_SERVICE:-}"
SERVER_PORT="${SERVER_PORT:-3000}"

APP_BASENAME=$(basename "$APP_FILE")

# Teardown handler — MUST be set before any exit-able code (source, SSH, install).
# Guards do_uninstall with a function check since substrate scripts may not be sourced yet.
SERVER_BRIDGE_PID=""
EXIT_CODE=0
teardown() {
    log "Teardown starting..."
    if [ -n "$SERVER_BRIDGE_PID" ]; then
        kill "$SERVER_BRIDGE_PID" 2>/dev/null || true
        log "Server bridge stopped."
    fi
    if type do_uninstall >/dev/null 2>&1; then
        do_uninstall
    fi
    remote "rm -rf $REMOTE_APP_DIR" 2>/dev/null || true
    kill $(jobs -p) 2>/dev/null
    wait 2>/dev/null
    log "Teardown complete."
    exit $EXIT_CODE
}
trap teardown TERM INT EXIT

# Source substrate-specific functions: do_install, do_inject_config, do_launch, do_uninstall
log "Substrate: $SMITHR_SUBSTRATE"
case "$SMITHR_SUBSTRATE" in
  physical) . /opt/scripts/physical-install.sh ;;
  *)        . /opt/scripts/simulated-install.sh ;;
esac

# Wait for SSH
wait_for_ssh

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
        EXIT_CODE=1
        exit 1
    fi
fi

# Launch
do_launch

# Signal healthy
mark_ready /tmp/app-installed
log "Ready."

# Stay alive — sleep in background so trap fires promptly
while true; do sleep 60 & wait $!; done
