#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
#
# delete-build-user.sh — Delete a macOS build user and home directory
#
# Installed at /Users/smithr/bin/delete-build-user.sh on macOS VMs.
# Called by Smithr (smithr.macos) via SSH as smithr.
#
# Usage:
#   delete-build-user.sh <username>
#
# Example:
#   delete-build-user.sh build-a1b2c3d4

set -euo pipefail

USERNAME="${1:?Usage: delete-build-user.sh <username>}"

# Safety: refuse to delete non-build users
case "$USERNAME" in
    smithr|claude|root|daemon|nobody)
        echo "ERROR: Refusing to delete system user: ${USERNAME}" >&2
        exit 1
        ;;
esac

# Check if user exists
if ! dscl . -read "/Users/${USERNAME}" UniqueID >/dev/null 2>&1; then
    echo "User ${USERNAME} does not exist" >&2
    exit 0
fi

echo "Deleting user ${USERNAME}..." >&2

# Kill any processes owned by this user
sudo pkill -u "${USERNAME}" 2>/dev/null || true
sleep 1

# Remove from SSH access group
sudo dscl . -delete /Groups/com.apple.access_ssh GroupMembership "${USERNAME}" 2>/dev/null || true

# Delete user account
sudo dscl . -delete "/Users/${USERNAME}"

# Delete home directory (check both RAM disk and /Users)
RAMDISK_HOME="/Volumes/BuildHomes/${USERNAME}"
if [[ -d "$RAMDISK_HOME" ]]; then
    sudo rm -rf "$RAMDISK_HOME"
fi
sudo rm -rf "/Users/${USERNAME}"

echo "Deleted user ${USERNAME}" >&2
