#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# Start XCTest runner on an iOS device via DVT protocol.
#
# Runs inside the bridge container where the RSD tunnel's TUN device
# is directly accessible — no relay needed. The DVT connection goes
# through tun0, which is stable (the Python relay was dropping
# connections after ~25 seconds).
#
# Usage: start-xctest.sh [BUNDLE_ID]
#   BUNDLE_ID defaults to XCTEST_BUNDLE_ID env var, or care.artha.maestro-driver-tests
#
# Requires:
#   - RSD tunnel running (tun0 up, /tmp/rsd-ready present)
#   - XCTest apps installed on the device
#
# The XCTest runner starts an HTTP server on the device (port 22087).
# Use iproxy on the host to forward this port:
#   iproxy -u <UDID> 22087:22087
set -e

# XCTest bundle ID — default is Artha's driver; override via XCTEST_BUNDLE_ID env var
# or pass as first argument.
BUNDLE_ID="${1:-${XCTEST_BUNDLE_ID:-care.artha.maestro-driver-tests}}"

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

# PORT=22087 is required: without it, Maestro's internal ViewHierarchyHandlerTests
# runs first (alphabetical), tries to launch org.wikimedia.wikipedia, and blocks
# the HTTP server from starting. With PORT set, that test skips via XCTSkip.
exec pymobiledevice3 developer dvt xcuitest \
  --rsd "$RSD_IPV6" "$RSD_PORT" \
  --env PORT=22087 \
  "$BUNDLE_ID"
