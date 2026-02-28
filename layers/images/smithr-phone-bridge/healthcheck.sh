#!/bin/sh
# Healthcheck — verify device protocol is responsive through the bridge.

PLATFORM="${PLATFORM:-android}"

case "$PLATFORM" in
  ios)
    # Lockdown bridge reachable
    bash -c 'exec 3<>/dev/tcp/localhost/62078 && exec 3>&-' || exit 1
    # RSD relay alive (only if tunnel started)
    if [ -f /tmp/rsd-relay.pid ]; then
      kill -0 "$(cat /tmp/rsd-relay.pid)" 2>/dev/null || exit 1
    fi
    ;;
  *)
    # ADB connectivity through bridge
    adb connect localhost:5555 >/dev/null 2>&1
    adb -s localhost:5555 shell echo ok 2>/dev/null | grep -q ok
    ;;
esac
