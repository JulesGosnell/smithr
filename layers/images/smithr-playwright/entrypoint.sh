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

# ── Install dependencies if needed ──────────────────────────
# Use /tmp for pnpm store so root-owned files don't leak onto the host mount.
# --no-frozen-lockfile because CI=true defaults to frozen mode but the
# monorepo lockfile may have patchedDependencies mismatches.
if [ -d /app/apps/web ]; then
  cd /app
  # Check for the actual playwright binary, not just .pnpm dir — stale volumes
  # may have an incomplete install that tricks a directory-only check.
  if [ ! -x /app/node_modules/.bin/playwright ]; then
    log "Installing dependencies..."
    pnpm install --no-frozen-lockfile --store-dir /tmp/pnpm-store 2>&1 | tail -10
    log "Dependencies installed."
  else
    log "Dependencies already installed, skipping."
  fi
fi

# ── Ready ────────────────────────────────────────────────────
touch /tmp/playwright-ready
log "Ready. Run tests via: docker exec <container> /usr/local/bin/run-playwright.sh <spec>"

# Stay alive — sleep in background so trap fires promptly
cleanup() {
  log "Shutting down."
  exit 0
}
trap cleanup TERM INT

while true; do sleep 60 & wait $!; done
