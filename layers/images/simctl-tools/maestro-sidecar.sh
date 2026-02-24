#!/bin/sh
#
# iOS Maestro sidecar — persistent container for running Maestro tests.
# Stays alive as a sidecar; run tests via:
#
#   docker exec <container> /run-test.sh <flow-file>
#
# Maestro for iOS uses XCTest which requires running on the same host
# as the Simulator. This sidecar SSHes into the macOS VM to execute tests.
#
set -e

SSH_TARGET="${SSH_TARGET:-ios-phone:22}"
SSH_USER="${SSH_USER:-smithr}"
SSH_KEY="${SSH_KEY:-}"

# Extract host:port
SSH_HOST="${SSH_TARGET%%:*}"
SSH_PORT="${SSH_TARGET##*:}"

KEY_OPT=""
if [ -n "$SSH_KEY" ] && [ -f "$SSH_KEY" ]; then
    KEY_OPT="-i $SSH_KEY"
fi

COMMON_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR $KEY_OPT"
SSH_OPTS="$COMMON_OPTS -p $SSH_PORT"

remote() {
    ssh $SSH_OPTS "$SSH_USER@$SSH_HOST" "$@"
}

# Wait for SSH to be reachable
echo "[ios-maestro] Waiting for SSH at $SSH_TARGET..."
for i in $(seq 1 60); do
    if remote "echo ok" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
echo "[ios-maestro] SSH connected."

# Verify Maestro is available on the VM
echo "[ios-maestro] Checking Maestro installation..."
if remote "eval \$(/usr/libexec/path_helper -s) && which maestro" >/dev/null 2>&1; then
    MAESTRO_VERSION=$(remote "eval \$(/usr/libexec/path_helper -s) && maestro --version" 2>/dev/null || echo "unknown")
    echo "[ios-maestro] Maestro $MAESTRO_VERSION found on VM."
else
    echo "[ios-maestro] WARNING: Maestro not found on macOS VM. Tests will fail."
fi

# Signal healthy
touch /tmp/maestro-ready
echo "[ios-maestro] Sidecar ready."
echo "[ios-maestro] Run tests via: docker exec <container> /run-test.sh <flow-file>"

# Stay alive
exec sleep infinity
