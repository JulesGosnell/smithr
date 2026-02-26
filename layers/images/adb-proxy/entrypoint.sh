#!/bin/sh
# Physical phone bridge — proxies ADB from host bridge to container port 5555.
#
# The host runs:  adb forward + socat (managed by smithr.devices)
# This container:  socat bridging container:5555 → host:BRIDGE_PORT
#
# From the outside, the container looks like any Android phone with ADB on 5555.
#
# Env vars:
#   BRIDGE_HOST  — Host address to reach the bridge (default: 10.21.0.1)
#   BRIDGE_PORT  — Host port where the ADB bridge is listening (required)
#   SERIAL       — Device serial number (informational)
set -e

: "${BRIDGE_PORT:?BRIDGE_PORT is required}"
BRIDGE_HOST="${BRIDGE_HOST:-10.21.0.1}"

echo "[phone-bridge] Device: ${SERIAL:-unknown}"
echo "[phone-bridge] Bridge: ${BRIDGE_HOST}:${BRIDGE_PORT} → container:5555"

exec socat TCP-LISTEN:5555,fork,reuseaddr "TCP:${BRIDGE_HOST}:${BRIDGE_PORT}"
