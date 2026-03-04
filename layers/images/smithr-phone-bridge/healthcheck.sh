#!/bin/sh
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# Healthcheck — verify device protocol is responsive through the bridge.

PLATFORM="${PLATFORM:-android}"

case "$PLATFORM" in
  ios)
    # Lockdown bridge reachable
    bash -c 'exec 3<>/dev/tcp/localhost/62078 && exec 3>&-' || exit 1
    # RSD tunnel alive (if started)
    if [ -f /tmp/rsd-ready ]; then
      ip link show tun0 >/dev/null 2>&1 || exit 1
    fi
    ;;
  *)
    # TCP connectivity to ADB bridge port
    bash -c 'exec 3<>/dev/tcp/localhost/5555 && exec 3>&-' || exit 1
    ;;
esac
