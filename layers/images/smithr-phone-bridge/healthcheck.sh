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
    # TCP connectivity to ADB bridge port
    bash -c 'exec 3<>/dev/tcp/localhost/5555 && exec 3>&-' || exit 1
    ;;
esac
