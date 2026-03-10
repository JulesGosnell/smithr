#!/usr/bin/env bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

# --- Config ---
SMITHR_MODE="${SMITHR_MODE:?SMITHR_MODE required (lease or adopt)}"
SMITHR_URL="${SMITHR_URL:?SMITHR_URL required (e.g. http://10.21.0.1:7070)}"
SMITHR_PORTS="${SMITHR_PORTS:?SMITHR_PORTS required (comma-separated canonical ports)}"
SMITHR_LESSEE="${SMITHR_LESSEE:-anonymous}"
SMITHR_TTL="${SMITHR_TTL:-3600}"
SMITHR_GATEWAY="${SMITHR_GATEWAY:-10.21.0.1}"
SMITHR_RETRY_MAX="${SMITHR_RETRY_MAX:-5}"
SMITHR_RETRY_DELAY="${SMITHR_RETRY_DELAY:-3}"

# Lease mode vars
SMITHR_RESOURCE_TYPE="${SMITHR_RESOURCE_TYPE:-}"
SMITHR_PLATFORM="${SMITHR_PLATFORM:-}"
SMITHR_LEASE_TYPE="${SMITHR_LEASE_TYPE:-phone}"
SMITHR_WORKSPACE="${SMITHR_WORKSPACE:-}"
SMITHR_SUBSTRATE="${SMITHR_SUBSTRATE:-}"
SMITHR_MODEL="${SMITHR_MODEL:-}"
SMITHR_TUNNEL_PROTOCOL="${SMITHR_TUNNEL_PROTOCOL:-}"

# Reverse tunnel vars (build leases only)
# Format: "bind:host:tunnel_port,..." — Smithr creates -R tunnels in its SSH session.
# The host field is ignored (Smithr routes to localhost:tunnel_port).
SMITHR_REVERSE_PORTS="${SMITHR_REVERSE_PORTS:-}"

# Adopt mode vars
SMITHR_CONTAINER_NAME="${SMITHR_CONTAINER_NAME:-}"

# --- State ---
PROXY_ID=""        # lease or adopt ID for cleanup
PROXY_TYPE=""      # "lease" or "adopt" — for DELETE path
SOCAT_PIDS=()
EXIT_CODE=0
PORTS_FILE="/tmp/smithr-proxy.ports"
ID_FILE="/tmp/smithr-proxy.id"
META_FILE="/tmp/smithr-proxy.meta"
DIRECT_FILE="/tmp/smithr-proxy.direct"
BACKENDS_FILE="/tmp/smithr-proxy.backends"
BACKEND_FAIL_COUNT=0
BACKEND_FAIL_MAX=3  # Exit after 3 consecutive backend failures (~15s)

log() { echo "[smithr-proxy] $*" >&2; }

die() { log "FATAL: $*"; EXIT_CODE=1; exit 1; }

# --- Cleanup ---
cleanup() {
  log "shutting down..."
  for pid in "${SOCAT_PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done

  if [[ -n "$PROXY_ID" && -n "$PROXY_TYPE" ]]; then
    case "$PROXY_TYPE" in
      lease)
        log "unleasing $PROXY_ID"
        curl -sf -X DELETE "${SMITHR_URL}/api/leases/${PROXY_ID}" || log "unlease failed (GC will clean up)"
        ;;
      adopt)
        log "unadopting $PROXY_ID"
        curl -sf -X DELETE "${SMITHR_URL}/api/adopts/${PROXY_ID}" || log "unadopt failed (GC will clean up)"
        ;;
    esac
  fi

  log "done"
  exit "$EXIT_CODE"
}

trap cleanup SIGTERM SIGINT
trap 'EXIT_CODE=$?; cleanup' EXIT

# --- API call with retries ---
api_call() {
  local method="$1" url="$2" body="${3:-}"
  local attempt=0 response=""

  while (( attempt < SMITHR_RETRY_MAX )); do
    attempt=$((attempt + 1))
    log "API $method $url (attempt $attempt/$SMITHR_RETRY_MAX)"

    local http_code
    if [[ -n "$body" ]]; then
      response=$(curl -s -w '\n%{http_code}' -X "$method" \
        -H 'Content-Type: application/json' \
        -d "$body" "$url" 2>/dev/null) || true
    else
      response=$(curl -s -w '\n%{http_code}' -X "$method" "$url" 2>/dev/null) || true
    fi

    http_code=$(echo "$response" | tail -1)
    response=$(echo "$response" | sed '$d')

    case "$http_code" in
      200|201)
        echo "$response"
        return 0
        ;;
      409)
        log "resource unavailable (409), retrying in ${SMITHR_RETRY_DELAY}s..."
        sleep "$SMITHR_RETRY_DELAY"
        ;;
      *)
        log "API error: HTTP $http_code"
        if (( attempt < SMITHR_RETRY_MAX )); then
          log "retrying in ${SMITHR_RETRY_DELAY}s..."
          sleep "$SMITHR_RETRY_DELAY"
        fi
        ;;
    esac
  done

  die "API call failed after $SMITHR_RETRY_MAX attempts"
}

# --- Start socat forwarders ---
# Args: pairs of "canonical_port:tunnel_port"
start_socats() {
  local port_list=""
  > "$BACKENDS_FILE"  # truncate backends file
  for pair in "$@"; do
    local canonical="${pair%%:*}"
    local tunnel="${pair##*:}"
    log "forwarding :${canonical} -> ${SMITHR_GATEWAY}:${tunnel}"
    socat TCP-LISTEN:"$canonical",fork,reuseaddr TCP:"$SMITHR_GATEWAY":"$tunnel" &
    SOCAT_PIDS+=($!)
    port_list="${port_list}${canonical}\n"
    echo "${SMITHR_GATEWAY}:${tunnel}" >> "$BACKENDS_FILE"
  done
  # Write ports file for healthcheck
  printf "%b" "$port_list" > "$PORTS_FILE"
}

# --- Parse reverse ports into JSON for lease API ---
# Input: SMITHR_REVERSE_PORTS="5593:10.21.0.1:17009,3000:192.168.0.75:17006"
# Output: JSON array [{"bind":5593,"host":"10.21.0.1","target":17009},...]
# Smithr creates -R bind:host:target in its SSH session.
build_reverse_ports_json() {
  [[ -z "$SMITHR_REVERSE_PORTS" ]] && return 0

  local result="["
  local first=true
  IFS=',' read -ra RPORTS <<< "$SMITHR_REVERSE_PORTS"
  for rport in "${RPORTS[@]}"; do
    rport=$(echo "$rport" | tr -d ' ')
    local bind_port target_host target_port
    # Format: bind:host:port
    bind_port="${rport%%:*}"
    target_port="${rport##*:}"
    # Extract middle field (host) — everything between first and last colon
    local rest="${rport#*:}"
    target_host="${rest%:*}"

    if [[ "$first" == "true" ]]; then
      first=false
    else
      result="${result},"
    fi
    result="${result}{\"bind\":${bind_port},\"host\":\"${target_host}\",\"target\":${target_port}}"
    log "reverse port: remote:${bind_port} → ${target_host}:${target_port}"
  done
  result="${result}]"
  echo "$result"
}

# --- Lease mode ---
do_lease() {
  [[ -z "$SMITHR_RESOURCE_TYPE" ]] && die "SMITHR_RESOURCE_TYPE required for lease mode"
  [[ -z "$SMITHR_PLATFORM" ]] && die "SMITHR_PLATFORM required for lease mode"

  # Build request body
  local body
  body=$(jq -n \
    --arg type "$SMITHR_RESOURCE_TYPE" \
    --arg platform "$SMITHR_PLATFORM" \
    --arg lessee "$SMITHR_LESSEE" \
    --argjson ttl "$SMITHR_TTL" \
    --arg lease_type "$SMITHR_LEASE_TYPE" \
    '{type: $type, platform: $platform, lessee: $lessee, ttl_seconds: $ttl, lease_type: $lease_type}')

  if [[ -n "$SMITHR_WORKSPACE" ]]; then
    body=$(echo "$body" | jq --arg ws "$SMITHR_WORKSPACE" '. + {workspace: $ws}')
  fi
  if [[ -n "$SMITHR_SUBSTRATE" ]]; then
    body=$(echo "$body" | jq --arg s "$SMITHR_SUBSTRATE" '. + {substrate: $s}')
  fi
  if [[ -n "$SMITHR_MODEL" ]]; then
    body=$(echo "$body" | jq --arg m "$SMITHR_MODEL" '. + {model: $m}')
  fi
  if [[ -n "$SMITHR_TUNNEL_PROTOCOL" ]]; then
    body=$(echo "$body" | jq --arg tp "$SMITHR_TUNNEL_PROTOCOL" '. + {tunnel_protocol: $tp}')
  fi

  # Add server_ports for multi-port leases
  IFS=',' read -ra PORTS <<< "$SMITHR_PORTS"
  if (( ${#PORTS[@]} > 1 )); then
    local ports_json
    ports_json=$(printf '%s\n' "${PORTS[@]}" | jq -R 'tonumber' | jq -s '.')
    body=$(echo "$body" | jq --argjson sp "$ports_json" '. + {server_ports: $sp}')
  fi

  # Add reverse_ports for build leases (Smithr creates -R tunnels in its SSH session)
  if [[ -n "$SMITHR_REVERSE_PORTS" && "$SMITHR_LEASE_TYPE" == "build" ]]; then
    local rp_json
    rp_json=$(build_reverse_ports_json)
    body=$(echo "$body" | jq --argjson rp "$rp_json" '. + {reverse_ports: $rp}')
  fi

  local response
  response=$(api_call POST "${SMITHR_URL}/api/leases" "$body")

  PROXY_ID=$(echo "$response" | jq -r '.id')
  PROXY_TYPE="lease"
  echo "$PROXY_ID" > "$ID_FILE"
  log "acquired lease $PROXY_ID"

  # Write metadata for workspace-ssh helper
  local ssh_user ssh_host resource_id
  ssh_user=$(echo "$response" | jq -r '.connection.ssh_user // empty')
  resource_id=$(echo "$response" | jq -r '.resource_id // empty')
  {
    echo "LEASE_ID=$PROXY_ID"
    echo "RESOURCE_ID=$resource_id"
    [[ -n "$ssh_user" ]] && echo "SSH_USER=$ssh_user"
    [[ -n "$SMITHR_WORKSPACE" ]] && echo "WORKSPACE=$SMITHR_WORKSPACE"
  } > "$META_FILE"

  # Extract tunnel port(s) and start socat
  local pairs=()
  if (( ${#PORTS[@]} == 1 )); then
    local tunnel_port
    tunnel_port=$(echo "$response" | jq -r '.connection.tunnel_port')
    pairs+=("${PORTS[0]}:${tunnel_port}")
  else
    # Multi-port: connection.tunnel_ports is a map {"5432": 17005, ...}
    for port in "${PORTS[@]}"; do
      local tunnel_port
      tunnel_port=$(echo "$response" | jq -r ".connection.tunnel_ports[\"$port\"] // .connection.tunnel_port")
      pairs+=("${port}:${tunnel_port}")
    done
  fi

  # Write direct tunnel endpoints (bypass socat — for external consumers)
  # Format: canonical_port:gateway:tunnel_port (e.g. 5555:10.21.0.1:17005)
  for pair in "${pairs[@]}"; do
    local canonical="${pair%%:*}"
    local tunnel="${pair##*:}"
    echo "${canonical}:${SMITHR_GATEWAY}:${tunnel}"
  done > "$DIRECT_FILE"
  log "direct tunnel endpoints written to $DIRECT_FILE"

  start_socats "${pairs[@]}"

  # For build leases: write SSH port to metadata so workspace-ssh can find it
  if [[ "$SMITHR_LEASE_TYPE" == "build" ]]; then
    local canonical_port="${pairs[0]%%:*}"
    echo "SSH_PORT=$canonical_port" >> "$META_FILE"
    # Default SSH_USER to workspace name if not returned by API
    if ! grep -q '^SSH_USER=' "$META_FILE"; then
      if [[ -n "$SMITHR_WORKSPACE" ]]; then
        echo "SSH_USER=$SMITHR_WORKSPACE" >> "$META_FILE"
      fi
    fi
    log "workspace metadata written to $META_FILE"

    # Reverse tunnels are now managed by Smithr (via -R flags on the SSH tunnel).
    # No separate SSH session needed — Smithr's tunnel process handles both
    # the forward (-L for SSH access) and reverse (-R for phone/server) paths.
    if [[ -n "$SMITHR_REVERSE_PORTS" ]]; then
      log "reverse tunnels managed by Smithr (passed via lease API)"
    fi
  fi
}

# --- Adopt mode ---
do_adopt() {
  [[ -z "$SMITHR_CONTAINER_NAME" ]] && die "SMITHR_CONTAINER_NAME required for adopt mode"

  IFS=',' read -ra PORTS <<< "$SMITHR_PORTS"
  local ports_json
  ports_json=$(printf '%s\n' "${PORTS[@]}" | jq -R 'tonumber' | jq -s '.')

  local body
  body=$(jq -n \
    --arg container "$SMITHR_CONTAINER_NAME" \
    --argjson ports "$ports_json" \
    --arg lessee "$SMITHR_LESSEE" \
    --argjson ttl "$SMITHR_TTL" \
    '{container_name: $container, ports: $ports, lessee: $lessee, ttl_seconds: $ttl}')

  local response
  response=$(api_call POST "${SMITHR_URL}/api/adopt" "$body")

  PROXY_ID=$(echo "$response" | jq -r '.id')
  PROXY_TYPE="adopt"
  echo "$PROXY_ID" > "$ID_FILE"
  log "adopted $SMITHR_CONTAINER_NAME as $PROXY_ID"

  # Extract tunnel port map and start socat
  local pairs=()
  for port in "${PORTS[@]}"; do
    local tunnel_port
    tunnel_port=$(echo "$response" | jq -r ".ports[\"$port\"]")
    pairs+=("${port}:${tunnel_port}")
  done

  start_socats "${pairs[@]}"
}

# --- Main ---
log "starting in $SMITHR_MODE mode"

case "$SMITHR_MODE" in
  lease) do_lease ;;
  adopt) do_adopt ;;
  *) die "SMITHR_MODE must be 'lease' or 'adopt', got '$SMITHR_MODE'" ;;
esac

log "proxy ready, waiting for socat processes"

# Wait for any socat to exit — if one dies, log it but keep running
# Also monitor backend tunnel health — exit if tunnel dies (prevents stale proxies)
while true; do
  for i in "${!SOCAT_PIDS[@]}"; do
    pid="${SOCAT_PIDS[$i]}"
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" 2>/dev/null || true
      log "WARNING: socat (pid $pid) exited"
      unset 'SOCAT_PIDS[$i]'
    fi
  done
  if [[ ${#SOCAT_PIDS[@]} -eq 0 ]]; then
    die "all socat processes exited"
  fi

  # Check backend tunnel health (skip during startup)
  if [[ -f "$BACKENDS_FILE" ]]; then
    backend_ok=true
    while IFS=: read -r host port; do
      [[ -z "$host" || -z "$port" ]] && continue
      if ! socat -T1 /dev/null "TCP:${host}:${port},connect-timeout=2" 2>/dev/null; then
        backend_ok=false
        break
      fi
    done < "$BACKENDS_FILE"
    if [[ "$backend_ok" == "true" ]]; then
      BACKEND_FAIL_COUNT=0
    else
      BACKEND_FAIL_COUNT=$((BACKEND_FAIL_COUNT + 1))
      log "WARNING: backend tunnel unreachable ($BACKEND_FAIL_COUNT/$BACKEND_FAIL_MAX)"
      if (( BACKEND_FAIL_COUNT >= BACKEND_FAIL_MAX )); then
        die "backend tunnel dead for $((BACKEND_FAIL_MAX * 5))s — exiting to allow fresh proxy"
      fi
    fi
  fi

  sleep 5
done
