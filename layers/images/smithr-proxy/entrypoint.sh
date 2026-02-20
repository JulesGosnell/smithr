#!/usr/bin/env bash
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
SMITHR_PREFER_HOST="${SMITHR_PREFER_HOST:-}"

# Reverse tunnel vars (build leases only)
SMITHR_REVERSE_PORTS="${SMITHR_REVERSE_PORTS:-}"  # e.g. "5555,3000,443"

# Adopt mode vars
SMITHR_CONTAINER_NAME="${SMITHR_CONTAINER_NAME:-}"

# --- State ---
PROXY_ID=""        # lease or adopt ID for cleanup
PROXY_TYPE=""      # "lease" or "adopt" — for DELETE path
SOCAT_PIDS=()
REVERSE_SSH_PID="" # PID of reverse tunnel SSH session
EXIT_CODE=0
PORTS_FILE="/tmp/smithr-proxy.ports"
ID_FILE="/tmp/smithr-proxy.id"
META_FILE="/tmp/smithr-proxy.meta"

log() { echo "[smithr-proxy] $*" >&2; }

die() { log "FATAL: $*"; EXIT_CODE=1; exit 1; }

# --- Cleanup ---
cleanup() {
  log "shutting down..."
  if [[ -n "$REVERSE_SSH_PID" ]]; then
    log "stopping reverse tunnel (pid $REVERSE_SSH_PID)"
    kill "$REVERSE_SSH_PID" 2>/dev/null || true
  fi
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
  for pair in "$@"; do
    local canonical="${pair%%:*}"
    local tunnel="${pair##*:}"
    log "forwarding :${canonical} -> ${SMITHR_GATEWAY}:${tunnel}"
    socat TCP-LISTEN:"$canonical",fork,reuseaddr TCP:"$SMITHR_GATEWAY":"$tunnel" &
    SOCAT_PIDS+=($!)
    port_list="${port_list}${canonical}\n"
  done
  # Write ports file for healthcheck
  printf "%b" "$port_list" > "$PORTS_FILE"
}

# --- Reverse tunnels (build leases) ---
# Opens an SSH connection to the workspace VM with -R forwards so the VM
# can reach services on the CI runner host (e.g., phone ADB, server, HTTPS).
# Chain: VM:port → SSH → proxy → 10.21.0.1:port → host:port
start_reverse_tunnels() {
  [[ -z "$SMITHR_REVERSE_PORTS" ]] && return 0

  local key="/run/secrets/ssh-key"
  if [[ ! -f "$key" ]]; then
    log "WARNING: SSH key not mounted, skipping reverse tunnels"
    return 0
  fi

  # Read SSH metadata
  local ssh_user ssh_port
  ssh_user=$(grep '^SSH_USER=' "$META_FILE" | cut -d= -f2-) || true
  ssh_port=$(grep '^SSH_PORT=' "$META_FILE" | cut -d= -f2-) || true

  if [[ -z "$ssh_user" || -z "$ssh_port" ]]; then
    log "WARNING: no SSH_USER/SSH_PORT in metadata, skipping reverse tunnels"
    return 0
  fi

  # Build -R flags
  # Supports two formats:
  #   Simple:   "5555,3000"       → -R 5555:GATEWAY:5555 -R 3000:GATEWAY:3000
  #   Extended: "5555:host:1234"  → -R 5555:host:1234  (pass through as-is)
  local ssh_args=()
  IFS=',' read -ra RPORTS <<< "$SMITHR_REVERSE_PORTS"
  for rport in "${RPORTS[@]}"; do
    rport=$(echo "$rport" | tr -d ' ')
    if [[ "$rport" == *:* ]]; then
      # Extended format: local_port:target_host:target_port — use as-is
      ssh_args+=(-R "$rport")
      log "reverse tunnel: VM:${rport%%:*} → ${rport#*:}"
    else
      # Simple format: just a port number — route via gateway
      ssh_args+=(-R "${rport}:${SMITHR_GATEWAY}:${rport}")
      log "reverse tunnel: VM:${rport} → ${SMITHR_GATEWAY}:${rport}"
    fi
  done

  # Wait for socat to be ready (SSH port must be forwarding)
  local retries=0
  while ! socat -T1 /dev/null "TCP:localhost:${ssh_port},connect-timeout=2" 2>/dev/null; do
    retries=$((retries + 1))
    if (( retries > 10 )); then
      log "WARNING: SSH port not ready after 10 attempts, skipping reverse tunnels"
      return 0
    fi
    sleep 1
  done

  # Open persistent SSH connection with reverse forwards
  ssh -N \
    -i "$key" \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -o IdentitiesOnly=yes \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -o ExitOnForwardFailure=yes \
    -o LogLevel=ERROR \
    -p "$ssh_port" \
    "${ssh_args[@]}" \
    "${ssh_user}@localhost" &
  REVERSE_SSH_PID=$!
  log "reverse tunnels started (pid $REVERSE_SSH_PID)"
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
  if [[ -n "$SMITHR_PREFER_HOST" ]]; then
    body=$(echo "$body" | jq --arg h "$SMITHR_PREFER_HOST" '. + {prefer_host: $h}')
  fi

  # Add server_ports for multi-port leases
  IFS=',' read -ra PORTS <<< "$SMITHR_PORTS"
  if (( ${#PORTS[@]} > 1 )); then
    local ports_json
    ports_json=$(printf '%s\n' "${PORTS[@]}" | jq -R 'tonumber' | jq -s '.')
    body=$(echo "$body" | jq --argjson sp "$ports_json" '. + {server_ports: $sp}')
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

    # Start reverse tunnels if requested
    start_reverse_tunnels
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
  # Monitor reverse tunnel SSH process
  if [[ -n "$REVERSE_SSH_PID" ]] && ! kill -0 "$REVERSE_SSH_PID" 2>/dev/null; then
    log "WARNING: reverse tunnel SSH (pid $REVERSE_SSH_PID) exited"
    REVERSE_SSH_PID=""
  fi
  sleep 2
done
