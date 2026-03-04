#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
#
# fix-runtime-match.sh — Set SDK-to-runtime override for Xcode 16.2
#
# Run this INSIDE the macOS VM via SSH while booted in persistent mode.
#
# Problem: Xcode 16.2 SDK (build 22C146 = iOS 18.2) expects runtime 22C146,
#          but Apple only offers iOS 18.3.1 (build 22D8075) for download.
#          Without an override, xcodebuild fails with:
#            "No simulator runtime version from [<DVTBuildVersion 22D8075>]
#             available to use with iphonesimulator SDK version <DVTBuildVersion 22C146>"
#
# Solution: Use `xcrun simctl runtime match set` to tell CoreSimulator
#           that SDK 22C146 should use runtime 22D8075.
#
# Usage (from Linux host):
#   docker exec smithr-xcode-fe scp -P 10022 -i /ssh-key/macos-ssh-key \
#     /tmp/fix-runtime-match.sh smithr@localhost:/tmp/fix-runtime-match.sh
#   docker exec smithr-xcode-fe ssh -p 10022 -i /ssh-key/macos-ssh-key \
#     smithr@localhost 'bash /tmp/fix-runtime-match.sh'

set -euo pipefail

log() { echo "[fix-match] $*" >&2; }

log "=== Step 1: Current runtime state ==="
xcrun simctl runtime list 2>&1 | head -20

log "=== Step 2: Current runtime match (BEFORE) ==="
xcrun simctl runtime match list 2>&1

log "=== Step 3: Get installed runtime build version ==="
# Extract the build version of the installed runtime — format: "iOS 18.3.1 (22D8075)"
RUNTIME_BUILD=$(xcrun simctl runtime list 2>&1 | grep -oE '\([A-Z0-9]+\)' | head -1 | tr -d '()')
if [[ -z "$RUNTIME_BUILD" ]]; then
    log "ERROR: No runtime installed. Run fix-base-image.sh first."
    exit 1
fi
log "Installed runtime build: $RUNTIME_BUILD"

log "=== Step 4: Get SDK build version ==="
SDK_BUILD=$(xcodebuild -version -sdk iphonesimulator 2>/dev/null | grep ProductBuildVersion | awk '{print $2}')
if [[ -z "$SDK_BUILD" ]]; then
    log "ERROR: Cannot determine SDK build version"
    exit 1
fi
log "SDK build: $SDK_BUILD"

if [[ "$SDK_BUILD" == "$RUNTIME_BUILD" ]]; then
    log "SDK and runtime builds match — no override needed!"
else
    log "SDK ($SDK_BUILD) != runtime ($RUNTIME_BUILD) — setting override..."

    log "=== Step 5: Set runtime match override ==="
    # Get the SDK platform identifier (e.g., "iphoneos18.2")
    SDK_VERSION=$(xcodebuild -version -sdk iphonesimulator 2>/dev/null | grep SDKVersion | awk '{print $2}')
    PLATFORM_KEY="iphoneos${SDK_VERSION}"
    log "Platform key: $PLATFORM_KEY"

    xcrun simctl runtime match set "$PLATFORM_KEY" "$RUNTIME_BUILD" 2>&1
    log "Override set: $PLATFORM_KEY -> $RUNTIME_BUILD"
fi

log "=== Step 6: Verify runtime match (AFTER) ==="
xcrun simctl runtime match list 2>&1

log "=== Step 7: Verify simulator destinations available ==="
# Quick check: can xcodebuild see any simulator destinations?
xcodebuild -showsdks 2>&1 | grep -i simulator || log "WARNING: No simulator SDKs found"

log "=== Step 8: List available simulator devices ==="
xcrun simctl list devices available 2>&1 | head -20

log "=== Step 9: Test xcodebuild destination discovery ==="
# This is the command that was failing — test it directly
xcodebuild -scheme DummyScheme -destination 'platform=iOS Simulator,name=iPhone 16' -showBuildSettings 2>&1 | head -5 || {
    # Expected to fail (no DummyScheme) but should NOT fail with runtime mismatch error
    LAST_ERROR=$?
    log "xcodebuild returned $LAST_ERROR (expected — no DummyScheme, but checking error type...)"
}

# Better test: just list destinations
log "=== Step 10: Full destination check ==="
xcodebuild -showdestinations -scheme nonexistent 2>&1 | head -20 || true

log "=== DONE ==="
log ""
log "Runtime match state:"
xcrun simctl runtime match list 2>&1 | grep -A3 iphoneos
log ""
log "If User Override shows $RUNTIME_BUILD, the fix is applied."
log ""
log "Next steps:"
log "  1. Clean shutdown: sudo shutdown -h now"
log "  2. Wait for VM to fully stop"
log "  3. Remove container: docker rm smithr-xcode-fe"
log "  4. Restart volatile: docker compose ... up -d"
log "  5. Verify: ssh in and run 'xcrun simctl runtime match list'"
