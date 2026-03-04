#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
#
# Refresh Smithr on one or both hosts: git pull, restart service.
#
# Usage:
#   bin/refresh.sh          # Both hosts
#   bin/refresh.sh mega     # megalodon only
#   bin/refresh.sh prog     # prognathodon only
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCAL_HOST="$(hostname)"

log() { echo "[refresh] $*" >&2; }

refresh_local() {
  log "=== $LOCAL_HOST (local) ==="
  cd "$PROJECT_DIR"

  # Stop
  if [[ -f smithr.pid ]]; then
    local pid
    pid=$(cat smithr.pid)
    if kill "$pid" 2>/dev/null; then
      log "Stopped PID $pid"
      sleep 3
    else
      log "PID $pid already dead"
    fi
    rm -f smithr.pid
  fi

  # Pull
  git pull --ff-only
  log "Code: $(git rev-parse --short HEAD)"

  # Start
  nohup clojure -M:run > "/tmp/smithr-${LOCAL_HOST}.log" 2>&1 </dev/null &
  disown
  sleep 2
  local java_pid
  java_pid=$(pgrep -f 'java.*smithr.core' | head -1)
  echo "$java_pid" > smithr.pid
  log "Started PID $java_pid"

  # Wait for healthy
  for i in $(seq 1 30); do
    if curl -sf http://localhost:7070/api/health >/dev/null 2>&1; then
      log "Healthy ($(curl -sf http://localhost:7070/api/health | python3 -c 'import sys,json; d=json.load(sys.stdin); print("resources=%d, hosts=%d" % (d["resources"], d["hosts"]))'))"
      return 0
    fi
    sleep 2
  done
  log "WARNING: not healthy after 60s — check /tmp/smithr-${LOCAL_HOST}.log"
  return 1
}

refresh_remote() {
  local host="$1"
  log "=== $host (remote) ==="

  # Stop
  ssh "$host" "cd ~/src/smithr && if [ -f smithr.pid ]; then kill \$(cat smithr.pid) 2>/dev/null && echo stopped || echo 'already dead'; rm -f smithr.pid; fi"
  sleep 3

  # Pull
  local sha
  sha=$(ssh "$host" "cd ~/src/smithr && git pull --ff-only >&2 && git rev-parse --short HEAD")
  log "Code: $sha"

  # Start — double-redirect so SSH session can close immediately
  ssh -f "$host" "cd ~/src/smithr && nohup clojure -M:run > /tmp/smithr-${host}.log 2>&1 </dev/null &"
  sleep 2
  local java_pid
  java_pid=$(ssh "$host" "pgrep -f 'java.*smithr.core' | head -1")
  ssh "$host" "echo $java_pid > ~/src/smithr/smithr.pid"
  log "Started PID $java_pid"

  # Wait for healthy
  for i in $(seq 1 30); do
    if ssh "$host" "curl -sf http://localhost:7070/api/health >/dev/null 2>&1"; then
      log "Healthy"
      return 0
    fi
    sleep 2
  done
  log "WARNING: not healthy after 60s — check /tmp/smithr-${host}.log on $host"
  return 1
}

# --- Main ---
TARGET="${1:-all}"

case "$TARGET" in
  mega*)
    if [[ "$LOCAL_HOST" == "megalodon" ]]; then
      refresh_local
    else
      refresh_remote megalodon
    fi
    ;;
  prog*)
    if [[ "$LOCAL_HOST" == "prognathodon" ]]; then
      refresh_local
    else
      refresh_remote prognathodon
    fi
    ;;
  all)
    refresh_local
    OTHER=$([[ "$LOCAL_HOST" == "megalodon" ]] && echo "prognathodon" || echo "megalodon")
    refresh_remote "$OTHER"
    ;;
  *)
    echo "Usage: bin/refresh.sh [mega|prog|all]" >&2
    exit 1
    ;;
esac

log "Done."
