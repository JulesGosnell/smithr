# Simulated iOS — xcrun simctl operations on macOS VM.
# Sourced by entrypoint.sh when SMITHR_SUBSTRATE=simulated (default).
#
# Expects: remote(), log(), BUNDLE_ID, REMOTE_APP_DIR, APP_BASENAME, API_URL

do_install() {
  remote "xcrun simctl install booted $REMOTE_APP_DIR/$APP_BASENAME"
}

do_inject_config() {
  if [ -n "$API_URL" ]; then
    log "Injecting api-config.json: $API_URL"
    DATA_CONTAINER=$(remote "xcrun simctl get_app_container booted $BUNDLE_ID data")
    DOCS_DIR="$DATA_CONTAINER/Documents"
    remote "mkdir -p $DOCS_DIR && echo '{\"apiUrl\": \"$API_URL\"}' > $DOCS_DIR/api-config.json"
    log "Config injected."
  fi
}

do_launch() {
  remote "xcrun simctl launch booted $BUNDLE_ID"
}

do_uninstall() {
  remote "xcrun simctl uninstall booted $BUNDLE_ID" 2>/dev/null || true
}
