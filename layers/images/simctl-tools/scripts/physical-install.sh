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
    # Push config into app's Documents directory via app-specific AFC
    remote "pymobiledevice3 apps push $BUNDLE_ID /tmp/api-config.json Documents/api-config.json" \
      && log "Config injected." \
      || log "WARNING: Config injection failed (app may use default URL)"
  fi
}

do_launch() {
  # Explicit launch via DVT protocol — Maestro's launchApp may not work
  # reliably through the external XCTest runner on physical devices.
  RSD_ADDR=$(remote "cat /tmp/rsd-ready" 2>/dev/null)
  if [ -n "$RSD_ADDR" ]; then
    RSD_IPV6=$(echo "$RSD_ADDR" | awk '{print $1}')
    RSD_PORT_VAL=$(echo "$RSD_ADDR" | awk '{print $2}')
    log "Launching $BUNDLE_ID via DVT (RSD: [$RSD_IPV6]:$RSD_PORT_VAL)..."
    remote "pymobiledevice3 developer dvt launch --rsd $RSD_IPV6 $RSD_PORT_VAL $BUNDLE_ID" \
      || log "WARNING: DVT launch failed (Maestro will retry via XCTest)"
  else
    log "WARNING: RSD tunnel not available, skipping explicit launch"
  fi
}

do_uninstall() {
  remote "pymobiledevice3 apps uninstall $BUNDLE_ID" 2>/dev/null || true
}
