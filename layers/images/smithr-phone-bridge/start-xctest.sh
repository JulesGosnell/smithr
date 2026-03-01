#!/bin/bash
# Start XCTest runner on an iOS device via DVT protocol.
#
# Runs inside the bridge container where the RSD tunnel's TUN device
# is directly accessible — no relay needed. The DVT connection goes
# through tun0, which is stable (the Python relay was dropping
# connections after ~25 seconds).
#
# Usage: start-xctest.sh [BUNDLE_ID]
#   BUNDLE_ID defaults to care.artha.maestro-driver-tests
#
# Requires:
#   - RSD tunnel running (tun0 up, /tmp/rsd-ready present)
#   - XCTest apps installed on the device
#
# The XCTest runner starts an HTTP server on the device (port 22087).
# Use iproxy on the host to forward this port:
#   iproxy -u <UDID> 22087:22087
set -e

BUNDLE_ID="${1:-care.artha.maestro-driver-tests}"

log() { echo "[xctest] $*" >&2; }

if [ ! -f /tmp/rsd-ready ]; then
  log "ERROR: RSD tunnel not ready (/tmp/rsd-ready missing)"
  exit 1
fi

RSD_IPV6=$(awk '{print $1}' /tmp/rsd-ready)
RSD_PORT=$(awk '{print $2}' /tmp/rsd-ready)

if [ -z "$RSD_IPV6" ] || [ -z "$RSD_PORT" ]; then
  log "ERROR: Could not read RSD address from /tmp/rsd-ready"
  exit 1
fi

log "Starting XCTest runner: bundle=$BUNDLE_ID rsd=[$RSD_IPV6]:$RSD_PORT"

exec pymobiledevice3 developer dvt xcuitest \
  --rsd "$RSD_IPV6" "$RSD_PORT" \
  "$BUNDLE_ID"
