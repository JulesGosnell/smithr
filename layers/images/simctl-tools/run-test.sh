#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
#
# Run a Maestro test (platform-agnostic).
# Called via: docker exec <container> /run-test.sh /flows/login.yaml [-e KEY=VAL ...]
#
# Env vars listed in MAESTRO_VARS are auto-forwarded as Maestro -e flags
# to the remote SSH session (worker/VM/bridge). Explicit -e flags from the
# command line are appended after and take precedence.
#
# Substrate dispatch:
#   android             — copy flows to worker, run Maestro there via SSH
#   simulated (default) — copy flows to VM, run Maestro there via SSH
#   physical            — copy flows to bridge, run Maestro there via SSH
#                         (bridge has iproxy → device:22087 → XCTest)
#
set -e

FLOW_FILE="${1:?Usage: run-test.sh <flow-file> [extra-args...]}"
shift
EXTRA_ARGS="$*"

# If FLOW_FILE is a relative path, assume it's relative to /flows (the mount point).
# Without this, dirname returns "." which expands to the container root.
if [ "${FLOW_FILE#/}" = "$FLOW_FILE" ]; then
  FLOW_FILE="/flows/$FLOW_FILE"
fi

SIDECAR_NAME="maestro"
REMOTE_FLOWS_DIR="${REMOTE_FLOWS_DIR:-/tmp/e2e-flows}"

# Maestro home for physical substrate (volume-mounted at runtime)
MAESTRO_HOME="${MAESTRO_HOME:-/opt/maestro}"

# Source auto-detected values from maestro-sidecar.sh.
# When called via docker exec, env vars like SMITHR_SUBSTRATE and SSH_TARGET
# are not in the Docker environment (they're set by the sidecar at runtime).
if [ -f /tmp/maestro-env ]; then
  . /tmp/maestro-env
fi
SMITHR_SUBSTRATE="${SMITHR_SUBSTRATE:-simulated}"

. /opt/scripts/ssh-common.sh
. /opt/scripts/common-funcs.sh

# Diagnostic logging — confirms env detection worked correctly.
echo "[$SIDECAR_NAME] substrate=$SMITHR_SUBSTRATE target=$SSH_HOST:$SSH_PORT user=$SSH_USER key=${SSH_KEY:+(set)} flow=$FLOW_FILE"

# Auto-forward env vars listed in MAESTRO_VARS as Maestro -e flags.
# This bridges the gap between the sidecar container (where compose env
# vars live) and the remote Maestro process (VM/bridge via SSH).
# APP_ID alias for BUNDLE_ID — Maestro flows use ${APP_ID}
# Must be exported so maestro_env_flags (printenv) can read it.
export APP_ID="${BUNDLE_ID:-${APP_ID:-}}"
AUTO_ENV_FLAGS=$(maestro_env_flags)
MAESTRO_EXTRA="${AUTO_ENV_FLAGS} ${MAESTRO_EXTRA:-} $EXTRA_ARGS"

case "$SMITHR_SUBSTRATE" in
  android)
    # Android: copy flows to worker, run Maestro there via SSH
    echo "[$SIDECAR_NAME] Copying flows to worker..."
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

    # Kill stale Maestro driver processes and clean ADB forwards.
    # Use 'pkill -9 java' — bare [java] processes evade 'pkill -f java.*maestro'
    # because their cmdline is truncated. Nothing else on the worker uses Java.
    remote "pkill -9 java 2>/dev/null; adb forward --remove-all 2>/dev/null; sleep 1" || true

    echo "[$SIDECAR_NAME] Running: maestro test $MAESTRO_EXTRA $REMOTE_FLOW"
    remote "maestro test $MAESTRO_EXTRA $REMOTE_FLOW"
    EXIT_CODE=$?

    remote "rm -rf $REMOTE_FLOWS_DIR" 2>/dev/null || true
    ;;

  physical)
    # Physical: copy flows to bridge, run Maestro there via SSH.
    # Same pattern as simulated (Maestro on VM via SSH).
    # Maestro on the bridge connects directly to localhost:22087 (via iproxy).
    echo "[$SIDECAR_NAME] Copying flows to bridge..."
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

    # Physical device: Maestro discovers device via fake xcrun (devicectl),
    # connects to XCTest HTTP server on localhost:22087 (via iproxy).
    # DeviceControlIOSDevice.launch() is patched to throw UnsupportedOperationException
    # so the fallback in LocalIOSDevice sends launch commands via XCTest HTTP API.
    echo "[$SIDECAR_NAME] Running (physical on bridge): maestro test --platform ios --apple-team-id SMITHR --no-reinstall-driver $MAESTRO_EXTRA $REMOTE_FLOW"
    remote "export USE_XCODE_TEST_RUNNER=1 && \
            export MAESTRO_CLI_NO_ANALYTICS=1 && \
            export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true && \
            export MAESTRO_OPTS='-Dmaestro.driver.port=22087' && \
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
    echo "[$SIDECAR_NAME] Copying flows to VM..."
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

    echo "[$SIDECAR_NAME] Running (simulated): maestro test $MAESTRO_EXTRA $REMOTE_FLOW"
    remote "eval \$(/usr/libexec/path_helper -s) && maestro test $MAESTRO_EXTRA $REMOTE_FLOW"
    EXIT_CODE=$?

    remote "rm -rf $REMOTE_FLOWS_DIR" 2>/dev/null || true
    ;;
esac

echo "[$SIDECAR_NAME] Maestro exited with code $EXIT_CODE"
exit $EXIT_CODE
