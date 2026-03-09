#!/usr/bin/env bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

T0=$(date +%s)
log() { echo "[playwright-sidecar] [$(( $(date +%s) - T0 ))s] $*" >&2; }

PLAYWRIGHT_BASE_URL="${PLAYWRIGHT_BASE_URL:?PLAYWRIGHT_BASE_URL required}"

# ── Wait for server to be reachable ──────────────────────────
log "Waiting for server at $PLAYWRIGHT_BASE_URL..."
for i in $(seq 1 120); do
  if curl -sf --max-time 5 "$PLAYWRIGHT_BASE_URL" >/dev/null 2>&1; then
    log "Server reachable after ${i}s."
    break
  fi
  if (( i == 120 )); then
    log "FATAL: Server not reachable after 120s"
    exit 1
  fi
  sleep 1
done

# ── Ready ────────────────────────────────────────────────────
# @playwright/test is baked into the image at /node_modules/ —
# no runtime install needed.
touch /tmp/playwright-ready
log "Ready. Run tests via: docker exec <container> /usr/local/bin/run-playwright.sh <spec>"

# Stay alive — sleep in background so trap fires promptly
cleanup() {
  log "Shutting down."
  exit 0
}
trap cleanup TERM INT

while true; do sleep 60 & wait $!; done
