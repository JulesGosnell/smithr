#!/bin/bash
#
# create-build-user.sh — Create a macOS user for an isolated build environment
#
# Installed at /Users/smithr/bin/create-build-user.sh on macOS VMs.
# Called by Hammar (hammar.macos) via SSH as smithr.
#
# Creates:
#   - macOS user with dscl
#   - Home directory
#   - SSH key auth (copied from smithr's authorized_keys)
#   - com.apple.access_ssh group membership
#   - Shell profile with PATH and locale
#
# Usage:
#   create-build-user.sh <username> [uid]
#
# Examples:
#   create-build-user.sh build-a1b2c3d4
#   create-build-user.sh artha-build-1 650

set -euo pipefail

USERNAME="${1:?Usage: create-build-user.sh <username> [uid]}"

# Use RAM disk for ephemeral build homes when available and has enough space.
# Minimum 4GB free required — iOS builds can use 25-30GB so fall back to /Users
# for large builds automatically.
RAMDISK_HOME="/Volumes/BuildHomes"
MIN_FREE_MB=4096
if [[ -d "$RAMDISK_HOME" ]] && mount | grep -q "$RAMDISK_HOME"; then
    AVAIL_MB=$(df -m "$RAMDISK_HOME" | awk 'NR==2 {print $4}')
    if [[ "$AVAIL_MB" -ge "$MIN_FREE_MB" ]]; then
        HOME_DIR="${RAMDISK_HOME}/${USERNAME}"
    else
        echo "RAM disk low (${AVAIL_MB}MB free < ${MIN_FREE_MB}MB min), using /Users" >&2
        HOME_DIR="/Users/${USERNAME}"
    fi
else
    HOME_DIR="/Users/${USERNAME}"
fi

# Find next UID if not specified
if [[ -n "${2:-}" ]]; then
    UID_NUM="$2"
else
    LAST_UID=$(dscl . -list /Users UniqueID | grep '^build-\|^[a-z].*-build' | awk '{print $2}' | sort -n | tail -1 2>/dev/null || echo "599")
    UID_NUM=$((LAST_UID + 1))
    # Ensure minimum UID of 600
    [[ $UID_NUM -ge 600 ]] || UID_NUM=600
fi

# Check if user already exists
if dscl . -read "/Users/${USERNAME}" UniqueID >/dev/null 2>&1; then
    echo "User ${USERNAME} already exists" >&2
    echo "${HOME_DIR}"
    exit 0
fi

echo "Creating user ${USERNAME} (UID: ${UID_NUM})..." >&2

# Create user account
sudo dscl . -create "/Users/${USERNAME}"
sudo dscl . -create "/Users/${USERNAME}" UserShell /bin/bash
sudo dscl . -create "/Users/${USERNAME}" RealName "Build ${USERNAME}"
sudo dscl . -create "/Users/${USERNAME}" UniqueID "${UID_NUM}"
sudo dscl . -create "/Users/${USERNAME}" PrimaryGroupID 20
sudo dscl . -create "/Users/${USERNAME}" NFSHomeDirectory "${HOME_DIR}"
sudo createhomedir -c -u "${USERNAME}" >/dev/null 2>&1 || sudo mkdir -p "${HOME_DIR}"

# Add to SSH access group (required by macOS for SSH login)
sudo dscl . -append /Groups/com.apple.access_ssh GroupMembership "${USERNAME}"

# Set up SSH key auth — copy from smithr admin user
sudo mkdir -p "${HOME_DIR}/.ssh"
sudo cp /Users/smithr/.ssh/authorized_keys "${HOME_DIR}/.ssh/"
sudo chown -R "${USERNAME}:staff" "${HOME_DIR}/.ssh"
sudo chmod 700 "${HOME_DIR}/.ssh"
sudo chmod 600 "${HOME_DIR}/.ssh/authorized_keys"

# Set up shell profile — PATH and locale for SSH sessions
sudo bash -c "printf 'eval \$(/usr/libexec/path_helper -s)\nexport LANG=en_US.UTF-8\nexport LC_ALL=en_US.UTF-8\n' > ${HOME_DIR}/.bashrc"
sudo bash -c "echo 'source ~/.bashrc' > ${HOME_DIR}/.bash_profile"
sudo chown "${USERNAME}:staff" "${HOME_DIR}/.bashrc" "${HOME_DIR}/.bash_profile"

# Copy CoreSimulator config from smithr (includes SDK-to-runtime overrides)
SMITHR_CORESIM="/Users/smithr/Library/Developer/CoreSimulator"
if [[ -f "${SMITHR_CORESIM}/RuntimeMap.plist" ]]; then
    sudo mkdir -p "${HOME_DIR}/Library/Developer/CoreSimulator"
    sudo cp "${SMITHR_CORESIM}/RuntimeMap.plist" "${HOME_DIR}/Library/Developer/CoreSimulator/"
    sudo chown -R "${USERNAME}:staff" "${HOME_DIR}/Library"
fi

echo "Created user ${USERNAME} at ${HOME_DIR}" >&2
echo "${HOME_DIR}"
