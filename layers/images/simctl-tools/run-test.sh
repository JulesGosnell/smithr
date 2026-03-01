#!/bin/bash
#
# Run a Maestro test on iOS.
# Called via: docker exec <container> /run-test.sh /flows/login.yaml
#
# Substrate dispatch:
#   simulated (default) — copy flows to VM, run Maestro there via SSH
#   physical            — copy flows to bridge, run Maestro there via SSH
#                         (bridge has iproxy → device:22087 → XCTest)
#
set -e

FLOW_FILE="${1:?Usage: run-test.sh <flow-file> [extra-args...]}"
shift
EXTRA_ARGS="$*"
MAESTRO_EXTRA="${MAESTRO_EXTRA:-} $EXTRA_ARGS"

SMITHR_SUBSTRATE="${SMITHR_SUBSTRATE:-simulated}"
SSH_TARGET="${SSH_TARGET:-ios-phone:22}"
SSH_KEY="${SSH_KEY:-}"
REMOTE_FLOWS_DIR="${REMOTE_FLOWS_DIR:-/tmp/e2e-flows}"

# Maestro home for physical substrate (volume-mounted at runtime)
MAESTRO_HOME="${MAESTRO_HOME:-/opt/maestro}"

# Default SSH_USER based on substrate
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

case "$SMITHR_SUBSTRATE" in
  physical)
    # Physical: copy flows to bridge, run Maestro there via SSH.
    # Same pattern as simulated (Maestro on VM via SSH).
    # Maestro on the bridge connects directly to localhost:22087 (via iproxy).
    echo "[ios-maestro] Copying flows to bridge..."
    remote "mkdir -p $REMOTE_FLOWS_DIR"

    if [ -d "$(dirname "$FLOW_FILE")" ]; then
      FLOWS_DIR=$(dirname "$FLOW_FILE")
      scp $SCP_OPTS -r "$FLOWS_DIR"/* "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/" 2>/dev/null || \
      scp $SCP_OPTS "$FLOW_FILE" "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/"
    else
      scp $SCP_OPTS "$FLOW_FILE" "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/"
    fi

    FLOW_BASENAME=$(basename "$FLOW_FILE")
    REMOTE_FLOW="$REMOTE_FLOWS_DIR/$FLOW_BASENAME"

    # Tell Maestro to use the externally-managed XCTest runner.
    # The sidecar starts the runner on the bridge; Maestro just connects
    # to localhost:22087 (iproxy → device:22087 → XCTest HTTP server).
    #
    # Flags:
    #   --apple-team-id    — required by 2.2.0 for physical devices (any non-null value)
    #   --no-reinstall-driver — skip xcodebuild (we start XCTest externally)
    #   USE_XCODE_TEST_RUNNER — wait for externally-started XCTest server
    echo "[ios-maestro] Running (physical on bridge): maestro test --platform ios --apple-team-id SMITHR --no-reinstall-driver $MAESTRO_EXTRA $REMOTE_FLOW"
    remote "export USE_XCODE_TEST_RUNNER=1 && \
            export MAESTRO_CLI_NO_ANALYTICS=1 && \
            export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true && \
            /opt/maestro/bin/maestro test \
              --platform ios \
              --apple-team-id SMITHR \
              --no-reinstall-driver \
              $MAESTRO_EXTRA $REMOTE_FLOW"
    EXIT_CODE=$?

    remote "rm -rf $REMOTE_FLOWS_DIR" 2>/dev/null || true
    ;;

  *)
    # Simulated: copy flows to VM and run Maestro there via SSH
    echo "[ios-maestro] Copying flows to VM..."
    remote "mkdir -p $REMOTE_FLOWS_DIR"

    if [ -d "$(dirname "$FLOW_FILE")" ]; then
      FLOWS_DIR=$(dirname "$FLOW_FILE")
      scp $SCP_OPTS -r "$FLOWS_DIR"/* "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/" 2>/dev/null || \
      scp $SCP_OPTS "$FLOW_FILE" "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/"
    else
      scp $SCP_OPTS "$FLOW_FILE" "$SSH_USER@$SSH_HOST:$REMOTE_FLOWS_DIR/"
    fi

    FLOW_BASENAME=$(basename "$FLOW_FILE")
    REMOTE_FLOW="$REMOTE_FLOWS_DIR/$FLOW_BASENAME"

    echo "[ios-maestro] Running (simulated): maestro test $MAESTRO_EXTRA $REMOTE_FLOW"
    remote "eval \$(/usr/libexec/path_helper -s) && maestro test $MAESTRO_EXTRA $REMOTE_FLOW"
    EXIT_CODE=$?

    remote "rm -rf $REMOTE_FLOWS_DIR" 2>/dev/null || true
    ;;
esac

echo "[ios-maestro] Maestro exited with code $EXIT_CODE"
exit $EXIT_CODE
