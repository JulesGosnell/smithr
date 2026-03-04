# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0

# Physical iOS — pymobiledevice3 operations on bridge container.
# Sourced by entrypoint.sh when SMITHR_SUBSTRATE=physical.
#
# The bridge container has pymobiledevice3, usbmuxd socket, and RSD tunnel.
# Commands run over SSH into the bridge (via ios-phone proxy).
#
# Expects: remote(), log(), BUNDLE_ID, REMOTE_APP_DIR, APP_BASENAME, API_URL

do_install() {
  RSD_ADDR=$(remote "cat /tmp/rsd-ready" 2>/dev/null)
  RSD_IPV6=$(echo "$RSD_ADDR" | awk '{print $1}')
  RSD_PORT_VAL=$(echo "$RSD_ADDR" | awk '{print $2}')
  remote "pymobiledevice3 apps install --rsd $RSD_IPV6 $RSD_PORT_VAL $REMOTE_APP_DIR/$APP_BASENAME"
}

do_inject_config() {
  if [ -n "$API_URL" ]; then
    log "Injecting api-config.json: $API_URL"
    remote "echo '{\"apiUrl\": \"$API_URL\"}' > /tmp/api-config.json"
    RSD_ADDR=$(remote "cat /tmp/rsd-ready" 2>/dev/null)
    RSD_IPV6=$(echo "$RSD_ADDR" | awk '{print $1}')
    RSD_PORT_VAL=$(echo "$RSD_ADDR" | awk '{print $2}')
    remote "pymobiledevice3 apps push --rsd $RSD_IPV6 $RSD_PORT_VAL $BUNDLE_ID /tmp/api-config.json Documents/api-config.json" \
      && log "Config injected." \
      || log "WARNING: Config injection failed (app may use default URL)"
  fi
}

do_launch() {
  # Don't launch the app here — let Maestro handle it.
  # Launching triggers the iOS "Local Network" permission dialog which
  # can only be dismissed by tapping the UI (Maestro's login flow does this).
  log "Skipping launch (Maestro will launch and dismiss network dialog)"
}

do_uninstall() {
  RSD_ADDR=$(remote "cat /tmp/rsd-ready" 2>/dev/null)
  if [ -n "$RSD_ADDR" ]; then
    RSD_IPV6=$(echo "$RSD_ADDR" | awk '{print $1}')
    RSD_PORT_VAL=$(echo "$RSD_ADDR" | awk '{print $2}')
    remote "pymobiledevice3 apps uninstall --rsd $RSD_IPV6 $RSD_PORT_VAL $BUNDLE_ID" 2>/dev/null || true
  fi
}
