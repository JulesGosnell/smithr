#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
#
# fix-base-image.sh — Fix smithr-sonoma base image
#
# Run this INSIDE the macOS VM via SSH while booted in persistent mode.
# It installs the correct iOS simulator runtime and cleans up stale state.
#
# Problem: Xcode 16.2 SDK (build 22C146 = iOS 18.2) has no matching runtime
#          available for download. Apple only offers iOS 18.3.1 (build 22D8075).
#          xcodebuild fails with "No simulator runtime version from [22D8075]
#          available to use with iphonesimulator SDK version 22C146"
#
# Solution: Download iOS 18.3.1 runtime + set runtime match override so
#           xcodebuild accepts 22D8075 for SDK 22C146.
#           See docs/IOS-RUNTIME-FIX.md for full details.
#
# Usage (from Linux host):
#   docker cp fix-base-image.sh smithr-xcode-fe:/tmp/
#   docker exec smithr-xcode-fe ssh -p 10022 -i /ssh-key/macos-ssh-key smithr@localhost 'bash /tmp/fix-base-image.sh'

set -euo pipefail

log() { echo "[fix-base] $*" >&2; }

log "=== Step 1: Clean up stale RuntimeMap files ==="
# These don't work for xcodebuild — remove them
sudo rm -f /Library/Developer/CoreSimulator/RuntimeMap.plist
rm -f ~/Library/Developer/CoreSimulator/RuntimeMap.plist
log "RuntimeMap files removed"

log "=== Step 2: Delete any stale build users ==="
for user in $(dscl . -list /Users | grep -E '^(artha|build)-'); do
    log "Deleting user: $user"
    sudo dscl . -delete "/Users/$user" 2>/dev/null || true
    sudo rm -rf "/Users/$user" "/Volumes/BuildHomes/$user" 2>/dev/null || true
done
log "Build users cleaned"

log "=== Step 3: Remove RAM disk LaunchDaemon ==="
sudo launchctl unload /Library/LaunchDaemons/com.smithr.ramdisk.plist 2>/dev/null || true
sudo rm -f /Library/LaunchDaemons/com.smithr.ramdisk.plist
log "RAM disk daemon removed"

log "=== Step 4: Unmount any RAM disks ==="
for vol in /Volumes/BuildHomes /Volumes/BuildCache; do
    if mount | grep -q "$vol"; then
        diskutil unmount force "$vol" 2>/dev/null || true
        log "Unmounted $vol"
    fi
done
log "RAM disks cleared"

log "=== Step 5: Check current runtime state ==="
xcrun simctl runtime list
RUNTIME_COUNT=$(xcrun simctl runtime list 2>/dev/null | grep "Total Disk Images:" | grep -oE '[0-9]+' | head -1)
log "Current runtime count: $RUNTIME_COUNT"

log "=== Step 6: Download iOS simulator runtime ==="
# With zero runtimes, xcodebuild -downloadPlatform should fetch the matching one
xcodebuild -downloadPlatform iOS 2>&1 || {
    log "Standard download failed, trying -downloadAllPlatforms..."
    xcodebuild -downloadAllPlatforms 2>&1 || {
        log "ERROR: Could not download any platform runtimes"
        exit 1
    }
}

log "=== Step 7: Verify runtime installed ==="
xcrun simctl runtime list
RUNTIME_COUNT=$(xcrun simctl runtime list 2>/dev/null | grep "Total Disk Images:" | grep -oE '[0-9]+' | head -1)
if [[ "$RUNTIME_COUNT" -eq 0 ]]; then
    log "ERROR: No runtimes installed after download"
    exit 1
fi

log "=== Step 8: Set runtime match override ==="
# Xcode 16.2 SDK (22C146) doesn't match any downloadable runtime exactly.
# Apple only offers 18.3.1 (22D8075). Force the mapping so xcodebuild works.
SDK_BUILD=$(xcodebuild -version -sdk iphonesimulator 2>/dev/null | grep ProductBuildVersion | awk '{print $2}')
SDK_VERSION=$(xcodebuild -version -sdk iphonesimulator 2>/dev/null | grep SDKVersion | awk '{print $2}')
RUNTIME_BUILD=$(xcrun simctl runtime list 2>&1 | grep -oE '\([A-Z0-9]+\)' | head -1 | tr -d '()')
if [[ "$SDK_BUILD" != "$RUNTIME_BUILD" ]]; then
    PLATFORM_KEY="iphoneos${SDK_VERSION}"
    log "SDK $SDK_BUILD != runtime $RUNTIME_BUILD, setting override: $PLATFORM_KEY -> $RUNTIME_BUILD"
    xcrun simctl runtime match set "$PLATFORM_KEY" "$RUNTIME_BUILD" 2>&1
    # Also install at system level so new build users inherit it
    sudo cp ~/Library/Developer/CoreSimulator/RuntimeMap.plist /Library/Developer/CoreSimulator/RuntimeMap.plist
    sudo chmod 644 /Library/Developer/CoreSimulator/RuntimeMap.plist
    log "Override set at user and system level"
else
    log "SDK and runtime match ($SDK_BUILD) — no override needed"
fi
xcrun simctl runtime match list 2>&1 | grep -A5 iphoneos

log "=== Step 9: Verify simulator devices available ==="
xcrun simctl list devices available | head -10

log "=== Step 10: Verify SSH environment and PATH ==="
cat ~/.ssh/environment 2>/dev/null || log "WARNING: No SSH environment file"
grep PermitUserEnvironment /etc/ssh/sshd_config | head -1

log "=== Step 11: Verify build user scripts ==="
ls -la ~/bin/*.sh 2>/dev/null || log "WARNING: No build scripts in ~/bin"

log "=== DONE ==="
log "Runtime state:"
xcrun simctl runtime list
log ""
log "Xcode: $(xcodebuild -version | head -1)"
log "SDK: $(xcodebuild -version -sdk iphonesimulator 2>/dev/null | grep ProductBuildVersion)"
log ""
log "Next steps:"
log "  1. Clean shutdown: sudo shutdown -h now"
log "  2. Remove container: docker rm smithr-xcode-fe"
log "  3. Restart volatile: docker compose ... up -d"
log "  4. Verify: ssh in and run 'xcrun simctl runtime list'"
