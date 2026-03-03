#!/usr/bin/env bash
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
if [ -d /app/apps/web ] && [ ! -d /app/node_modules/.pnpm ]; then
  log "Installing dependencies..."
  cd /app
  pnpm install --frozen-lockfile 2>&1 | tail -10
  log "Dependencies installed."
elif [ -d /app/apps/web ]; then
  log "Dependencies already installed, skipping."
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
