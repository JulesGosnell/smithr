#!/bin/bash
#
# Run a Maestro test on iOS.
# Called via: docker exec <container> /run-test.sh /flows/login.yaml
#
# Substrate dispatch:
#   simulated (default) — copy flows to VM, run Maestro there via SSH
#   physical            — copy flows locally, run Maestro here (connects
#                         to XCTest via SSH tunnel on localhost:22087)
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

# Maestro jar for physical substrate (volume-mounted at runtime)
MAESTRO_JAR="${MAESTRO_JAR:-/opt/maestro/maestro-cli.jar}"

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
    # Physical: run Maestro locally — it connects to the XCTest HTTP
    # server via the SSH tunnel (localhost:22087) set up by maestro-sidecar.sh
    FLOW_BASENAME=$(basename "$FLOW_FILE")
    LOCAL_FLOWS_DIR="/tmp/maestro-flows"
    mkdir -p "$LOCAL_FLOWS_DIR"

    # Copy flow files locally
    if [ -d "$(dirname "$FLOW_FILE")" ]; then
      FLOWS_DIR=$(dirname "$FLOW_FILE")
      cp -r "$FLOWS_DIR"/* "$LOCAL_FLOWS_DIR/" 2>/dev/null || \
      cp "$FLOW_FILE" "$LOCAL_FLOWS_DIR/"
    else
      cp "$FLOW_FILE" "$LOCAL_FLOWS_DIR/"
    fi

    LOCAL_FLOW="$LOCAL_FLOWS_DIR/$FLOW_BASENAME"

    echo "[ios-maestro] Running (physical): java -jar $MAESTRO_JAR test --platform ios --host 127.0.0.1 --port 22087 $MAESTRO_EXTRA $LOCAL_FLOW"
    java -jar "$MAESTRO_JAR" test \
      --platform ios --host 127.0.0.1 --port 22087 \
      $MAESTRO_EXTRA "$LOCAL_FLOW"
    EXIT_CODE=$?

    rm -rf "$LOCAL_FLOWS_DIR"
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
