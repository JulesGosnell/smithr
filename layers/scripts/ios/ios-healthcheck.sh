#!/bin/bash
#
# ios-healthcheck.sh
#
# Health check for iOS service - verifies Simulator and device are ready.
# Assumes macOS is already healthy (use macos-healthcheck.sh for that).
#
# Checks:
#   1. Simulator.app is running
#   2. Expected device is booted (if IOS_DEVICE is set)
#
# Returns 0 (healthy) only when ALL conditions are met.
# Returns 1 (unhealthy) if any check fails.
#
# Environment Variables:
#   IOS_DEVICE  - Expected simulator device name (optional)
#   SSH_USER    - SSH user for macOS VM (default: smithr)
#   SSH_KEY     - Path to SSH private key (default: /ssh-key/macos-ssh-key)
#

set -e

SSH_USER="${SSH_USER:-smithr}"
SSH_PORT="${SSH_PORT:-10022}"
SSH_HOST="${SSH_HOST:-localhost}"
SSH_KEY="${SSH_KEY:-/ssh-key/macos-ssh-key}"

# SSH options for non-interactive, no host key checking
if [[ -f "$SSH_KEY" ]]; then
    SSH_OPTS="-i $SSH_KEY -o ConnectTimeout=5 -o StrictHostKeyChecking=no -o BatchMode=yes -o LogLevel=ERROR"
else
    SSH_OPTS="-o ConnectTimeout=5 -o StrictHostKeyChecking=no -o BatchMode=yes -o LogLevel=ERROR"
fi

# Check 1: Simulator.app is running
if ! ssh $SSH_OPTS -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" "pgrep -x Simulator" >/dev/null 2>&1; then
    exit 1
fi

# Check 2: If IOS_DEVICE is set, verify that device is booted
if [[ -n "${IOS_DEVICE:-}" ]]; then
    booted_devices=$(ssh $SSH_OPTS -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" \
        "xcrun simctl list devices booted 2>/dev/null" 2>/dev/null || echo "")

    if ! echo "$booted_devices" | grep -qF "$IOS_DEVICE"; then
        exit 1
    fi
fi

# iOS is ready
exit 0
