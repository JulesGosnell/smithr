#!/bin/bash
#
# macos-healthcheck.sh
#
# Health check for macOS service - verifies the desktop is ready.
# Runs INSIDE the Docker container and SSHs into macOS VM.
#
# Checks:
#   1. SSH is responsive (macOS booted)
#   2. Dock is running (GUI/desktop ready)
#
# Returns 0 (healthy) only when BOTH conditions are met.
# Returns 1 (unhealthy) if any check fails.
#
# Environment Variables:
#   SSH_USER - SSH user for macOS VM (default: claude)
#   SSH_KEY  - Path to SSH private key (default: /ssh-key/macos-ssh-key)
#

set -e

SSH_USER="${SSH_USER:-claude}"
SSH_PORT="${SSH_PORT:-10022}"
SSH_HOST="${SSH_HOST:-localhost}"
SSH_KEY="${SSH_KEY:-/ssh-key/macos-ssh-key}"

# SSH options for non-interactive, no host key checking
if [[ -f "$SSH_KEY" ]]; then
    SSH_OPTS="-i $SSH_KEY -o ConnectTimeout=5 -o StrictHostKeyChecking=no -o BatchMode=yes -o LogLevel=ERROR"
else
    SSH_OPTS="-o ConnectTimeout=5 -o StrictHostKeyChecking=no -o BatchMode=yes -o LogLevel=ERROR"
fi

# If SSH key is missing, fall back to basic port check only
if [[ ! -f "$SSH_KEY" ]]; then
    if command -v nc &>/dev/null; then
        nc -z "$SSH_HOST" "$SSH_PORT" 2>/dev/null && exit 0 || exit 1
    else
        timeout 2 bash -c "echo >/dev/tcp/$SSH_HOST/$SSH_PORT" 2>/dev/null && exit 0 || exit 1
    fi
fi

# Check 1: SSH connectivity to macOS
if ! ssh $SSH_OPTS -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" "echo ok" >/dev/null 2>&1; then
    exit 1
fi

# Check 2: Dock is running (indicates GUI/desktop is ready)
if ! ssh $SSH_OPTS -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" "pgrep -x Dock" >/dev/null 2>&1; then
    exit 1
fi

# macOS desktop is ready
exit 0
