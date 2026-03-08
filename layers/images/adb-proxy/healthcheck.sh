#!/bin/sh
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# Healthcheck — verify ADB is responsive through the bridge.
if [ -n "${USB_SERIAL:-}" ]; then
  # USB mode — check via USB serial directly
  adb -s "$USB_SERIAL" shell getprop ro.build.display.id >/dev/null 2>&1
else
  adb connect localhost:5555 >/dev/null 2>&1
  adb -s localhost:5555 shell echo ok 2>/dev/null | grep -q ok
fi
