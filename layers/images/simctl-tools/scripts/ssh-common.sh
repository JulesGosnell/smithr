# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0

# ssh-common.sh — Shared SSH connection setup for iOS sidecars.
# Source this file to get SSH variables and the remote() function.
#
# Expects these env vars to be set (or uses defaults):
#   SSH_TARGET        — host:port (default: ios-phone:22)
#   SSH_KEY           — path to SSH key (optional)
#   SSH_USER          — SSH user (default: substrate-dependent)
#   SMITHR_SUBSTRATE  — simulated | physical (default: simulated)
#
# Provides:
#   SSH_HOST, SSH_PORT, KEY_OPT, COMMON_OPTS, SSH_OPTS, SCP_OPTS
#   remote()          — execute a command on the SSH target

SSH_TARGET="${SSH_TARGET:-ios-phone:22}"
SSH_KEY="${SSH_KEY:-}"
SMITHR_SUBSTRATE="${SMITHR_SUBSTRATE:-simulated}"

# Default SSH_USER based on substrate (simulated → macOS VM user, physical → bridge root)
if [ -z "$SSH_USER" ]; then
  case "$SMITHR_SUBSTRATE" in
    physical) SSH_USER="root" ;;
    *)        SSH_USER="smithr" ;;
  esac
fi

# Extract host:port
SSH_HOST="${SSH_TARGET%%:*}"
SSH_PORT="${SSH_TARGET##*:}"

KEY_OPT=""
if [ -n "$SSH_KEY" ] && [ -f "$SSH_KEY" ]; then
    KEY_OPT="-i $SSH_KEY"
fi

COMMON_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR $KEY_OPT"
SSH_OPTS="$COMMON_OPTS -p $SSH_PORT"
SCP_OPTS="$COMMON_OPTS -P $SSH_PORT"

remote() {
    ssh $SSH_OPTS "$SSH_USER@$SSH_HOST" "$@"
}
