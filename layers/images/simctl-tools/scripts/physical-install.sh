# Physical iOS — pymobiledevice3 operations on bridge container.
# Sourced by entrypoint.sh when SMITHR_SUBSTRATE=physical.
#
# The bridge container has pymobiledevice3, usbmuxd socket, and RSD tunnel.
# Commands run over SSH into the bridge (via ios-phone proxy).
#
# Expects: remote(), log(), BUNDLE_ID, REMOTE_APP_DIR, APP_BASENAME, API_URL

do_install() {
  remote "pymobiledevice3 apps install $REMOTE_APP_DIR/$APP_BASENAME"
}

do_inject_config() {
  if [ -n "$API_URL" ]; then
    log "Injecting api-config.json: $API_URL"
    remote "echo '{\"apiUrl\": \"$API_URL\"}' > /tmp/api-config.json"
    # Push config into app's Documents directory on device
    remote "pymobiledevice3 afc push /tmp/api-config.json Documents/api-config.json"
    log "Config injected."
  fi
}

do_launch() {
  # Physical devices: Maestro/XCTest handles app launch
  log "Skipping launch (physical — Maestro/XCTest will launch)"
}

do_uninstall() {
  remote "pymobiledevice3 apps uninstall $BUNDLE_ID" 2>/dev/null || true
}
