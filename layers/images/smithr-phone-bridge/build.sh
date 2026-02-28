#!/bin/bash
# Build the smithr-phone-bridge Docker image.
#
# Usage:
#   layers/images/smithr-phone-bridge/build.sh
#   layers/images/smithr-phone-bridge/build.sh --restart   # also restart bridge containers
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE="smithr-phone-bridge:latest"

log() { echo "[build] $*" >&2; }

log "Building ${IMAGE}..."
docker build -t "${IMAGE}" "${SCRIPT_DIR}"

if [ "$1" = "--restart" ]; then
  # Find all running bridge containers using the old image
  BRIDGES=$(docker ps --filter "ancestor=${IMAGE}" --filter "status=running" \
    --format '{{.Names}}' 2>/dev/null || true)

  if [ -z "$BRIDGES" ]; then
    # Fallback: find by name convention (physical-*)
    BRIDGES=$(docker ps --filter "name=physical-" --filter "status=running" \
      --format '{{.Names}}' 2>/dev/null || true)
  fi

  if [ -z "$BRIDGES" ]; then
    log "No running bridge containers to restart."
  else
    log "Restarting bridge containers on new image..."
    log "NOTE: Bridges will be recreated by Smithr's register-devices!"
    log "      Run: curl -sX POST localhost:7070/api/register-devices"
    log ""
    log "To restart manually, stop the old containers — register-devices!"
    log "will detect them as dead and recreate from the new image:"
    for C in $BRIDGES; do
      log "  docker stop $C && docker rm $C"
    done
  fi
fi

log "Done. Image: ${IMAGE}"
