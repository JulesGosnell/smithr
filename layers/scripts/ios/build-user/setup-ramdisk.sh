#!/bin/bash
#
# setup-ramdisk.sh — Create a RAM disk for ephemeral build user home directories
#
# macOS doesn't have tmpfs. Instead, we create a RAM disk via hdiutil/diskutil
# and mount it at /Volumes/BuildHomes. Build user home directories are created
# here for maximum I/O performance (no disk writes for ephemeral builds).
#
# Installed at /Users/smithr/bin/setup-ramdisk.sh on macOS VMs.
# Called by com.smithr.ramdisk.plist LaunchDaemon on boot.
#
# Usage:
#   setup-ramdisk.sh [size_mb]
#
# Default size: 2048 MB (2 GB)

set -euo pipefail

SIZE_MB="${1:-2048}"
MOUNT_POINT="/Volumes/BuildHomes"

# Convert MB to 512-byte sectors for hdiutil
SECTORS=$((SIZE_MB * 2048))

# Check if already mounted
if mount | grep -q "$MOUNT_POINT"; then
    echo "RAM disk already mounted at $MOUNT_POINT" >&2
    exit 0
fi

echo "Creating ${SIZE_MB}MB RAM disk at ${MOUNT_POINT}..." >&2

# Create RAM disk
DISK_DEV=$(hdiutil attach -nomount "ram://${SECTORS}")
DISK_DEV=$(echo "$DISK_DEV" | xargs)  # trim whitespace

# Format as APFS (faster than HFS+ for many small files)
diskutil eraseDisk APFS BuildHomes "$DISK_DEV" >/dev/null

# Verify mount
if mount | grep -q "$MOUNT_POINT"; then
    echo "RAM disk mounted: ${SIZE_MB}MB at ${MOUNT_POINT} (${DISK_DEV})" >&2
    # Set permissions so build users can have home dirs here
    chmod 755 "$MOUNT_POINT"
else
    echo "ERROR: RAM disk created but not mounted at ${MOUNT_POINT}" >&2
    exit 1
fi
