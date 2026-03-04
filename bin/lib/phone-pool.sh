#!/usr/bin/env bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# smithr/bin/lib/phone-pool.sh — Phone pool management core
#
# Manages warm pools of Android emulators and iOS simulators.
# Phones can be acquired (leased), used for testing, and unleased back.
#
# State is tracked in a JSON file on NFS for cross-machine coordination.
# Uses flock-based locking for concurrent access safety.
#
# State file: /srv/shared/smithr/phone-pool/state.json
# Lock file:  /srv/shared/smithr/phone-pool/state.lock

set -euo pipefail

SMITHR_ROOT="${SMITHR_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
source "${SMITHR_ROOT}/bin/lib/common.sh"

# Configuration
PHONE_POOL_DIR="${SMITHR_PHONE_POOL_DIR:-/srv/shared/smithr/phone-pool}"
PHONE_STATE_FILE="${PHONE_POOL_DIR}/state.json"
PHONE_LOCK_FILE="${PHONE_POOL_DIR}/state.lock"
PHONE_LEASE_TTL="${SMITHR_PHONE_LEASE_TTL:-1800}"  # 30 minutes default

# Ensure pool directory exists
ensure_pool_dir() {
    mkdir -p "${PHONE_POOL_DIR}"
    if [[ ! -f "$PHONE_STATE_FILE" ]]; then
        echo '{"phones":[],"leases":[]}' > "$PHONE_STATE_FILE"
    fi
}

# Acquire a lock on the state file
# Usage: with_lock <command>
with_lock() {
    ensure_pool_dir
    (
        flock -w 10 200 || die "Cannot acquire phone pool lock (timeout)"
        "$@"
    ) 200>"$PHONE_LOCK_FILE"
}

# Read the current state
read_state() {
    cat "$PHONE_STATE_FILE"
}

# Write new state
write_state() {
    local new_state="$1"
    echo "$new_state" > "${PHONE_STATE_FILE}.tmp"
    mv "${PHONE_STATE_FILE}.tmp" "$PHONE_STATE_FILE"
}

# Register a warm phone in the pool
# Usage: pool_register <host> <platform> <type> <port> <ip> [extra_json]
pool_register() {
    local host="$1" platform="$2" type="$3" port="$4" ip="$5"
    local extra="${6:-{}}"
    local now
    now=$(date +%s)

    local phone_id="${host}:${platform}:${port}"

    local state
    state=$(read_state)

    # Check if already registered
    if echo "$state" | jq -e ".phones[] | select(.id == \"${phone_id}\")" >/dev/null 2>&1; then
        log_warn "Phone already registered: ${phone_id}"
        return 0
    fi

    # Add to pool
    local new_phone
    new_phone=$(jq -n \
        --arg id "$phone_id" \
        --arg host "$host" \
        --arg platform "$platform" \
        --arg type "$type" \
        --argjson port "$port" \
        --arg ip "$ip" \
        --argjson registered "$now" \
        --arg status "warm" \
        '{id: $id, host: $host, platform: $platform, type: $type, port: $port, ip: $ip, registered: $registered, status: $status}')

    state=$(echo "$state" | jq ".phones += [$new_phone]")
    write_state "$state"

    log_ok "Registered phone: ${phone_id} (${type})"
}

# Acquire (lease) a phone from the pool
# Usage: pool_acquire <platform> <type> [consumer_id]
# Returns: phone handle (host:platform:port) on stdout
pool_acquire() {
    local platform="$1"
    local type="${2:-any}"
    local consumer="${3:-$$}"
    local now
    now=$(date +%s)

    local state
    state=$(read_state)

    # Find a warm phone matching criteria
    local query=".phones[] | select(.status == \"warm\" and .platform == \"${platform}\""
    if [[ "$type" != "any" ]]; then
        query="${query} and .type == \"${type}\""
    fi
    query="${query})"

    local phone
    phone=$(echo "$state" | jq -r "[$query] | first // empty")

    if [[ -z "$phone" ]]; then
        log_error "No warm ${platform} phone available (type: ${type})"
        return 1
    fi

    local phone_id
    phone_id=$(echo "$phone" | jq -r '.id')

    # Mark as leased
    local lease_expires=$((now + PHONE_LEASE_TTL))
    state=$(echo "$state" | jq "(.phones[] | select(.id == \"${phone_id}\")).status = \"leased\"")

    # Add lease record
    local lease
    lease=$(jq -n \
        --arg phone_id "$phone_id" \
        --arg consumer "$consumer" \
        --argjson acquired "$now" \
        --argjson expires "$lease_expires" \
        '{phone_id: $phone_id, consumer: $consumer, acquired: $acquired, expires: $expires}')

    state=$(echo "$state" | jq ".leases += [$lease]")
    write_state "$state"

    log_ok "Acquired phone: ${phone_id} (consumer: ${consumer}, TTL: ${PHONE_LEASE_TTL}s)"
    echo "$phone_id"
}

# Unlease a phone back to the pool
# Usage: pool_unlease <phone_id>
pool_unlease() {
    local phone_id="$1"

    local state
    state=$(read_state)

    # Check phone exists and is leased
    local phone_status
    phone_status=$(echo "$state" | jq -r ".phones[] | select(.id == \"${phone_id}\") | .status")

    if [[ "$phone_status" != "leased" ]]; then
        log_warn "Phone ${phone_id} is not leased (status: ${phone_status:-not_found})"
        return 1
    fi

    # Mark as cleaning, then warm
    state=$(echo "$state" | jq "(.phones[] | select(.id == \"${phone_id}\")).status = \"warm\"")

    # Remove lease
    state=$(echo "$state" | jq "del(.leases[] | select(.phone_id == \"${phone_id}\"))")

    write_state "$state"
    log_ok "Unleased phone: ${phone_id}"
}

# List all phones and their status
pool_list() {
    ensure_pool_dir
    local state
    state=$(read_state)

    echo "$state" | jq -r '.phones[] | "\(.id)\t\(.platform)\t\(.type)\t\(.status)"' | \
        column -t -s $'\t' -N "HANDLE,PLATFORM,TYPE,STATUS"
}

# Show pool status summary
pool_status() {
    ensure_pool_dir
    local state
    state=$(read_state)

    local total warm leased
    total=$(echo "$state" | jq '.phones | length')
    warm=$(echo "$state" | jq '[.phones[] | select(.status == "warm")] | length')
    leased=$(echo "$state" | jq '[.phones[] | select(.status == "leased")] | length')

    echo -e "${BOLD}Phone Pool Status${NC}"
    echo "  Total:  ${total}"
    echo -e "  Warm:   ${GREEN}${warm}${NC}"
    echo -e "  Leased: ${YELLOW}${leased}${NC}"

    if [[ $leased -gt 0 ]]; then
        echo ""
        echo -e "${BOLD}Active Leases:${NC}"
        echo "$state" | jq -r '.leases[] | "  \(.phone_id) → \(.consumer) (expires: \(.expires | todate))"'
    fi
}

# Reap expired leases (called by cron)
pool_reap() {
    local now
    now=$(date +%s)

    local state
    state=$(read_state)

    # Find expired leases
    local expired
    expired=$(echo "$state" | jq -r "[.leases[] | select(.expires < ${now})] | .[].phone_id")

    if [[ -z "$expired" ]]; then
        log_info "No expired leases"
        return 0
    fi

    while IFS= read -r phone_id; do
        log_warn "Reaping expired lease: ${phone_id}"
        state=$(echo "$state" | jq "(.phones[] | select(.id == \"${phone_id}\")).status = \"warm\"")
        state=$(echo "$state" | jq "del(.leases[] | select(.phone_id == \"${phone_id}\"))")
    done <<< "$expired"

    write_state "$state"
}
