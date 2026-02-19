#!/bin/sh
#
# ios-sim-boot.sh
#
# Boots the iOS Simulator in the macOS VM.
# Runs as a sidecar container after macOS is healthy.
# Does NOT install the app - that's handled during E2E tests.
#
# Environment Variables:
#   SSH_HOST      - macOS container hostname
#   SSH_PORT      - SSH port (default: 10022)
#   SSH_USER      - SSH user (default: smithr)
#   SSH_KEY       - Path to SSH private key
#   IOS_DEVICE    - Simulator device name
#   IOS_RUNTIME   - iOS runtime version
#

set -e

SSH_OPTS="-i $SSH_KEY -o ConnectTimeout=10 -o StrictHostKeyChecking=no -o BatchMode=yes -o LogLevel=ERROR"

log() {
    echo "[ios-sim-boot] $1"
}

ssh_cmd() {
    ssh $SSH_OPTS -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" "$@"
}

# Auto-match Xcode SDK to the installed simulator runtime.
# If Xcode 16.2 ships iOS 18.2 SDK but we have 18.3 runtime, Xcode won't
# find a matching runtime without this mapping. Idempotent — safe to run every boot.
log "Checking SDK-to-runtime mapping..."
SDK_VERSION=$(ssh_cmd "xcodebuild -version -sdk iphoneos SDKVersion 2>/dev/null" 2>/dev/null || echo "")
if [ -n "$SDK_VERSION" ]; then
    SDK_NAME="iphoneos${SDK_VERSION}"
    # Parse build number from: "iOS 18.3 (18.3 - 22D8075) - com.apple..."
    # Build numbers are like 22D8075: digits, uppercase letter, digits
    RUNTIME_BUILD=$(ssh_cmd "xcrun simctl list runtimes 2>/dev/null | grep 'iOS' | head -1 | grep -oE '[0-9]+[A-Z][0-9]+'" 2>/dev/null || echo "")
    if [ -n "$RUNTIME_BUILD" ]; then
        log "Mapping SDK $SDK_NAME → runtime build $RUNTIME_BUILD"
        ssh_cmd "xcrun simctl runtime match set $SDK_NAME $RUNTIME_BUILD" 2>/dev/null || true
    else
        log "WARNING: Could not detect iOS runtime build number"
    fi
else
    log "WARNING: Could not detect Xcode SDK version"
fi

# Shutdown all devices BEFORE opening Simulator.app
# If we open Simulator first, it auto-boots the default device AND opens windows
# for previously used devices. Shutting down first ensures a clean slate.
log "Shutting down any existing simulator devices..."
ssh_cmd "xcrun simctl shutdown all 2>/dev/null" || true
sleep 1

# Get device UUID for the specific runtime
# Use exact match pattern: device name followed by space and open paren
# This ensures "iPhone 16" doesn't match "iPhone 16 Pro"
log "Finding device UUID for $IOS_DEVICE on $IOS_RUNTIME..."
DEVICE_UUID=$(ssh_cmd "xcrun simctl list devices '$IOS_RUNTIME' 2>/dev/null | grep -F '$IOS_DEVICE (' | grep -oE '[0-9A-F-]{36}' | head -1" 2>/dev/null || echo "")

# Boot our specific device BEFORE opening Simulator.app
# This ensures Simulator opens with only our device's window
if [ -z "$DEVICE_UUID" ]; then
    log "WARNING: Could not find UUID for $IOS_DEVICE, using name-based boot"
    log "Booting device: $IOS_DEVICE"
    ssh_cmd "xcrun simctl boot '$IOS_DEVICE'" 2>/dev/null || true
else
    log "Booting device: $IOS_DEVICE [$DEVICE_UUID]"
    ssh_cmd "xcrun simctl boot '$DEVICE_UUID'" 2>/dev/null || true
fi

# Now open Simulator.app - it will show only the device we just booted
log "Starting Simulator.app..."
ssh_cmd "open -a Simulator" || true

# Wait for Simulator.app to be running
log "Waiting for Simulator.app..."
for i in $(seq 1 60); do
    if ssh_cmd "pgrep -x Simulator" >/dev/null 2>&1; then
        log "Simulator.app is running"
        break
    fi
    sleep 2
done

# Wait for device to finish booting
log "Waiting for device to boot..."
for i in $(seq 1 60); do
    # Use exact match to avoid "iPhone 16" matching "iPhone 16 Pro"
    BOOTED=$(ssh_cmd "xcrun simctl list devices booted 2>/dev/null | grep -cF '$IOS_DEVICE (' || true" 2>/dev/null)
    if [ "$BOOTED" = "1" ]; then
        log "Device booted successfully: $IOS_DEVICE"
        break
    fi
    sleep 2
done

log "Simulator boot complete!"

# Keep container running so healthcheck can verify
exec tail -f /dev/null
