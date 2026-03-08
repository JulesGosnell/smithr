#!/bin/sh
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# Physical phone bridge — proxies ADB to container port 5555.
#
# Two modes:
#   USB mode (USB_SERIAL set):
#     Container has /dev/bus/usb mounted. Starts ADB server, enables tcpip,
#     creates adb forward through USB transport, and socat exposes on 5555.
#     All data flows over USB — no WiFi latency.
#
#   WiFi mode (BRIDGE_PORT set):
#     The host runs adb forward + socat (managed by smithr.devices).
#     This container runs socat bridging container:5555 → host:BRIDGE_PORT.
#
# From the outside, both modes look like any Android phone with ADB on 5555.
#
# Env vars:
#   USB_SERIAL   — USB device serial (triggers USB mode)
#   BRIDGE_HOST  — Host address to reach the bridge (default: 10.21.0.1)
#   BRIDGE_PORT  — Host port where the ADB bridge is listening (WiFi mode)
#   SERIAL       — Device serial number (informational)
set -e

if [ -n "${USB_SERIAL:-}" ]; then
  # USB mode — device is connected via USB, not WiFi
  echo "[phone-bridge] USB mode: serial=$USB_SERIAL"
  adb start-server
  adb -s "$USB_SERIAL" wait-for-device
  # Enable TCP mode so adb forward can reach the ADB daemon
  adb -s "$USB_SERIAL" tcpip 5555 || true
  sleep 1
  # Forward through USB transport (not WiFi)
  adb -s "$USB_SERIAL" forward tcp:15555 tcp:5555
  echo "[phone-bridge] USB bridge ready: socat :5555 → adb forward :15555 → USB"
  exec socat TCP-LISTEN:5555,fork,reuseaddr TCP:localhost:15555
else
  # WiFi mode (original behavior)
  : "${BRIDGE_PORT:?BRIDGE_PORT is required (or set USB_SERIAL for USB mode)}"
  BRIDGE_HOST="${BRIDGE_HOST:-10.21.0.1}"

  echo "[phone-bridge] Device: ${SERIAL:-unknown}"
  echo "[phone-bridge] Bridge: ${BRIDGE_HOST}:${BRIDGE_PORT} → container:5555"

  exec socat TCP-LISTEN:5555,fork,reuseaddr "TCP:${BRIDGE_HOST}:${BRIDGE_PORT}"
fi
