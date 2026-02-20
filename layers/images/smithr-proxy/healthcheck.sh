#!/usr/bin/env bash
set -euo pipefail

PORTS_FILE="/tmp/smithr-proxy.ports"

if [[ ! -f "$PORTS_FILE" ]]; then
  exit 1
fi

while IFS= read -r port; do
  [[ -z "$port" ]] && continue
  if ! socat -T1 /dev/null "TCP:localhost:${port},connect-timeout=2" 2>/dev/null; then
    exit 1
  fi
done < "$PORTS_FILE"

exit 0
