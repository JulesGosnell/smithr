#!/usr/bin/env bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

PORTS_FILE="/tmp/smithr-proxy.ports"
BACKENDS_FILE="/tmp/smithr-proxy.backends"

# Check that proxy ports are set up
if [[ ! -f "$PORTS_FILE" ]]; then
  exit 1
fi

# Check local socat is listening
while IFS= read -r port; do
  [[ -z "$port" ]] && continue
  if ! socat -T1 /dev/null "TCP:localhost:${port},connect-timeout=2" 2>/dev/null; then
    exit 1
  fi
done < "$PORTS_FILE"

# Check backend tunnel is reachable (prevents stale proxy appearing healthy)
if [[ -f "$BACKENDS_FILE" ]]; then
  while IFS=: read -r host port; do
    [[ -z "$host" || -z "$port" ]] && continue
    if ! socat -T1 /dev/null "TCP:${host}:${port},connect-timeout=2" 2>/dev/null; then
      exit 1
    fi
  done < "$BACKENDS_FILE"
fi

exit 0
