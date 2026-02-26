#!/bin/sh
# Healthcheck — verify ADB is responsive through the bridge.
adb connect localhost:5555 >/dev/null 2>&1
adb -s localhost:5555 shell echo ok 2>/dev/null | grep -q ok
