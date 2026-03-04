#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# Build the smithr-phone-worker Docker image.
#
# Copies Maestro tarball and patched smithr jars from /srv/shared/images
# into the build context, builds the image, then cleans up.
#
# Usage:
#   layers/images/smithr-phone-worker/build.sh
#   layers/images/smithr-phone-worker/build.sh --push   # also push to registry
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SHARED="/srv/shared/images"
IMAGE="smithr-phone-worker:latest"

log() { echo "[build] $*" >&2; }

# Copy Maestro artifacts into build context
log "Copying Maestro tarball..."
cp "${SHARED}/maestro-2.2.0-smithr.tar.gz" "${SCRIPT_DIR}/maestro.tar.gz"

log "Copying patched smithr jars..."
cp "${SHARED}/maestro-cli-2.1.0-smithr.jar" "${SCRIPT_DIR}/maestro-cli-smithr.jar"
cp "${SHARED}/maestro-client-smithr.jar" "${SCRIPT_DIR}/maestro-client-smithr.jar"
cp "${SHARED}/maestro-utils-smithr.jar" "${SCRIPT_DIR}/maestro-utils-smithr.jar"
cp "${SHARED}/maestro-ios-driver-smithr.jar" "${SCRIPT_DIR}/maestro-ios-driver-smithr.jar"

# Build
log "Building ${IMAGE}..."
docker build -t "${IMAGE}" "${SCRIPT_DIR}"

# Clean up build context artifacts (large, shouldn't persist)
rm -f "${SCRIPT_DIR}/maestro.tar.gz" "${SCRIPT_DIR}/maestro-cli-smithr.jar" \
      "${SCRIPT_DIR}/maestro-client-smithr.jar" "${SCRIPT_DIR}/maestro-utils-smithr.jar" \
      "${SCRIPT_DIR}/maestro-ios-driver-smithr.jar"
log "Cleaned up build context."

# Optional push
if [ "$1" = "--push" ]; then
  REGISTRY="${SMITHR_REGISTRY:-localhost:5000}"
  REMOTE="${REGISTRY}/smithr/phone-worker:latest"
  log "Tagging and pushing to ${REMOTE}..."
  docker tag "${IMAGE}" "${REMOTE}"
  docker push "${REMOTE}"
  log "Pushed."
fi

log "Done. Image: ${IMAGE}"
