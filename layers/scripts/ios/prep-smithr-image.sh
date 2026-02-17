#!/bin/bash
#
# prep-smithr-image.sh
#
# Prepare a macOS VM image for Smithr:
#   1. Create 'smithr' admin user with SSH key auth
#   2. Grant passwordless sudo for user management commands
#   3. Verify smithr can create/delete build users
#   4. Optionally remove the old 'claude' user
#
# Run this OUTSIDE the VM — it SSHes in as the existing 'claude' user
# to bootstrap the 'smithr' user.
#
# Prerequisites:
#   - macOS VM is running with SMITHR_MACOS_PERSISTENT=1
#   - SSH access as 'claude' works
#
# Usage:
#   ./prep-smithr-image.sh [--ssh-port 50922] [--ssh-host localhost] [--remove-claude]
#

set -euo pipefail

SSH_PORT="${1:-50922}"
SSH_HOST="${2:-localhost}"
REMOVE_CLAUDE="${3:-}"
SSH_KEY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ssh/macos-ssh-key"
SSH_OPTS="-i $SSH_KEY -o StrictHostKeyChecking=no -o ConnectTimeout=30 -p $SSH_PORT"

log() { echo "[prep] $1" >&2; }

ssh_as() {
    local user="$1"; shift
    ssh $SSH_OPTS "${user}@${SSH_HOST}" "$@"
}

# --- Step 1: Verify connectivity as claude ---
log "Verifying SSH as claude..."
ssh_as claude "echo ok" >/dev/null 2>&1 || {
    echo "ERROR: Cannot SSH as claude@${SSH_HOST}:${SSH_PORT}" >&2
    exit 1
}
log "Connected to macOS VM"

# --- Step 2: Create smithr user ---
log "Creating smithr user..."
ssh_as claude "bash -s" <<'REMOTE'
set -e

# Check if smithr already exists
if dscl . -read /Users/smithr >/dev/null 2>&1; then
    echo "User 'smithr' already exists, skipping creation"
else
    # Find next available UID (500+ range for admin users)
    LAST_UID=$(dscl . -list /Users UniqueID | awk '{print $2}' | sort -n | tail -1)
    NEW_UID=$((LAST_UID + 1))

    echo "Creating user smithr with UID $NEW_UID..."
    sudo dscl . -create /Users/smithr
    sudo dscl . -create /Users/smithr UserShell /bin/bash
    sudo dscl . -create /Users/smithr RealName "Smithr Admin"
    sudo dscl . -create /Users/smithr UniqueID "$NEW_UID"
    sudo dscl . -create /Users/smithr PrimaryGroupID 80  # 80 = admin group
    sudo dscl . -create /Users/smithr NFSHomeDirectory /Users/smithr
    sudo createhomedir -c -u smithr 2>/dev/null || sudo mkdir -p /Users/smithr

    # Add to admin group (needed for sudo and dscl)
    sudo dscl . -append /Groups/admin GroupMembership smithr

    # Set a password (required for sudo, even with NOPASSWD)
    # Using a random password — SSH key is the real auth
    PASS=$(openssl rand -base64 24)
    sudo dscl . -passwd /Users/smithr "$PASS"
    echo "smithr user created (UID: $NEW_UID)"
fi
REMOTE

# --- Step 3: Set up SSH key auth for smithr ---
log "Setting up SSH key auth for smithr..."
PUBKEY=$(ssh-keygen -y -f "$SSH_KEY" 2>/dev/null)
ssh_as claude "bash -s" <<REMOTE
set -e
sudo mkdir -p /Users/smithr/.ssh
sudo bash -c "echo '$PUBKEY' > /Users/smithr/.ssh/authorized_keys"
sudo chown -R smithr:staff /Users/smithr/.ssh
sudo chmod 700 /Users/smithr/.ssh
sudo chmod 600 /Users/smithr/.ssh/authorized_keys
echo "SSH key installed for smithr"
REMOTE

# --- Step 4: Grant passwordless sudo ---
log "Configuring passwordless sudo for smithr..."
ssh_as claude "bash -s" <<'REMOTE'
set -e
SUDOERS_FILE="/etc/sudoers.d/smithr"
if [ -f "$SUDOERS_FILE" ]; then
    echo "Sudoers file already exists, updating..."
fi
# Grant full passwordless sudo — smithr is the admin user for the VM
sudo bash -c "cat > $SUDOERS_FILE" <<'EOF'
# Smithr admin — passwordless sudo for user management and system tasks
smithr ALL=(ALL) NOPASSWD: ALL
EOF
sudo chmod 440 "$SUDOERS_FILE"
# Validate
sudo visudo -cf "$SUDOERS_FILE"
echo "Sudoers configured"
REMOTE

# --- Step 5: Verify smithr SSH access ---
log "Verifying SSH as smithr..."
ssh_as smithr "whoami" || {
    echo "ERROR: Cannot SSH as smithr — check key setup" >&2
    exit 1
}
log "SSH as smithr works"

# --- Step 6: Verify smithr can create/delete users ---
log "Testing user management..."
ssh_as smithr "bash -s" <<'REMOTE'
set -e
TEST_USER="build-test1234"

# Create test user
sudo dscl . -create /Users/$TEST_USER
sudo dscl . -create /Users/$TEST_USER UserShell /bin/bash
sudo dscl . -create /Users/$TEST_USER RealName "Test Build"
sudo dscl . -create /Users/$TEST_USER UniqueID 600
sudo dscl . -create /Users/$TEST_USER PrimaryGroupID 20
sudo dscl . -create /Users/$TEST_USER NFSHomeDirectory /Users/$TEST_USER
sudo createhomedir -c -u $TEST_USER 2>/dev/null || sudo mkdir -p /Users/$TEST_USER

# Verify
ls -d /Users/$TEST_USER >/dev/null
echo "Created test user: $TEST_USER"

# Clean up
sudo dscl . -delete /Users/$TEST_USER
sudo rm -rf /Users/$TEST_USER
echo "Deleted test user: $TEST_USER"
echo "User management verified OK"
REMOTE

# --- Step 7: Optionally remove claude user ---
if [[ "$REMOVE_CLAUDE" == "--remove-claude" ]]; then
    log "Removing claude user..."
    ssh_as smithr "bash -s" <<'REMOTE'
set -e
if dscl . -read /Users/claude >/dev/null 2>&1; then
    sudo dscl . -delete /Users/claude
    sudo rm -rf /Users/claude
    echo "claude user removed"
else
    echo "claude user not found (already removed?)"
fi
REMOTE
else
    log "Keeping claude user (use --remove-claude to remove)"
fi

log ""
log "=== Image prep complete ==="
log "smithr user: created, admin, sudo, SSH key auth"
log "User management: verified (create + delete build users)"
log ""
log "Next steps:"
log "  1. Shut down the VM cleanly: ssh $SSH_OPTS smithr@${SSH_HOST} 'sudo shutdown -h now'"
log "  2. The image at smithr-sonoma.img is ready to use"
log "  3. Update SMITHR_MACOS_IMAGE to point to smithr-sonoma.img"
