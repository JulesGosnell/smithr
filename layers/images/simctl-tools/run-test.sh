#!/bin/sh
#
# Run a Maestro test on the iOS Simulator via SSH.
# Called via: docker exec <container> /run-test.sh /flows/login.yaml
#
# If the file is under /flows/, it is first copied to the VM.
# Extra Maestro args can be passed via MAESTRO_EXTRA env var.
#
set -e

FLOW_FILE="${1:?Usage: run-test.sh <flow-file> [extra-args...]}"
shift
EXTRA_ARGS="$*"
MAESTRO_EXTRA="${MAESTRO_EXTRA:-} $EXTRA_ARGS"

SSH_TARGET="${SSH_TARGET:-ios-phone:22}"
SSH_USER="${SSH_USER:-smithr}"
SSH_KEY="${SSH_KEY:-}"
REMOTE_FLOWS_DIR="${REMOTE_FLOWS_DIR:-/tmp/e2e-flows}"

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

# Copy flow files to VM
echo "[ios-maestro] Copying flows to VM..."
remote "mkdir -p $REMOTE_FLOWS_DIR"

if [ -d "$(dirname "$FLOW_FILE")" ]; then
    # Copy the entire flows directory for relative references
    FLOWS_DIR=$(dirname "$FLOW_FILE")
    scp $SCP_OPTS -r "$FLOWS_DIR"/* "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/" 2>/dev/null || \
    scp $SCP_OPTS "$FLOW_FILE" "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/"
else
    scp $SCP_OPTS "$FLOW_FILE" "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/"
fi

FLOW_BASENAME=$(basename "$FLOW_FILE")
REMOTE_FLOW="$REMOTE_FLOWS_DIR/$FLOW_BASENAME"

# Run Maestro on the VM (path_helper ensures correct PATH for SSH sessions)
echo "[ios-maestro] Running: maestro test $REMOTE_FLOW $MAESTRO_EXTRA"
remote "eval \$(/usr/libexec/path_helper -s) && maestro test $MAESTRO_EXTRA $REMOTE_FLOW"
EXIT_CODE=$?

# Clean up
remote "rm -rf $REMOTE_FLOWS_DIR" 2>/dev/null || true

echo "[ios-maestro] Maestro exited with code $EXIT_CODE"
exit $EXIT_CODE
