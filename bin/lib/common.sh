#!/usr/bin/env bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# smithr/bin/lib/common.sh — Shared utilities for all Smithr scripts
#
# Sourced by other scripts. Do not execute directly.

# Colours (disabled if not a terminal)
if [[ -t 1 ]]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[0;33m'
    BLUE='\033[0;34m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    RED='' GREEN='' YELLOW='' BLUE='' BOLD='' NC=''
fi

log_info()  { echo -e "${BLUE}[smithr]${NC} $*" >&2; }
log_ok()    { echo -e "${GREEN}[smithr]${NC} $*" >&2; }
log_warn()  { echo -e "${YELLOW}[smithr]${NC} $*" >&2; }
log_error() { echo -e "${RED}[smithr]${NC} $*" >&2; }

# Die with an error message
die() { log_error "$@"; exit 1; }

# Check that a command exists
require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

# Find Smithr root (directory containing bin/smithr)
find_smithr_root() {
    local dir="${SMITHR_ROOT:-$(cd "$(dirname "${BASH_SOURCE[1]}")/.." && pwd)}"
    if [[ ! -f "${dir}/bin/smithr" ]]; then
        die "Cannot find Smithr root. Expected bin/smithr at: ${dir}/bin/smithr"
    fi
    echo "$dir"
}

# Load smithr.yml configuration
# Parses YAML via Python and caches as JSON for jq queries
# Usage: load_config [path]
load_config() {
    local config_path="${1:-${SMITHR_CONFIG:-./smithr.yml}}"

    # Source the config parser
    local smithr_root
    smithr_root="${SMITHR_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
    source "${smithr_root}/bin/lib/config.sh"

    if [[ ! -f "$config_path" ]]; then
        log_warn "No smithr.yml found at: ${config_path}"
        return 0
    fi

    log_info "Loading config: ${config_path}"
    smithr_config_load "$config_path" || die "Failed to parse: ${config_path}"
}

# Wait for a Docker container's health check to pass
wait_for_healthy() {
    local container="$1"
    local timeout="${2:-120}"
    local elapsed=0
    local interval=5

    log_info "Waiting for ${container} to be healthy (timeout: ${timeout}s)..."

    while [[ $elapsed -lt $timeout ]]; do
        local status
        status=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "not_found")

        case "$status" in
            healthy)
                log_ok "${container} is healthy (${elapsed}s)"
                return 0
                ;;
            unhealthy)
                log_error "${container} is unhealthy after ${elapsed}s"
                return 1
                ;;
            not_found)
                # Container doesn't exist yet, keep waiting
                ;;
            *)
                # starting or other state
                ;;
        esac

        sleep "$interval"
        elapsed=$((elapsed + interval))
    done

    log_error "${container} did not become healthy within ${timeout}s"
    return 1
}

# Generate a unique compose project name
# Usage: make_project_name "ci" "training" → "smithr-ci-training-1707849600"
make_project_name() {
    local mode="$1"
    local name="$2"
    local timestamp
    timestamp=$(date +%s)
    echo "smithr-${mode}-${name}-${timestamp}"
}

# Check if running inside a container
is_in_container() {
    [[ -f /.dockerenv ]] || grep -qE '(docker|lxc|containerd)' /proc/1/cgroup 2>/dev/null
}

# Get the host's IP address on the default interface
get_host_ip() {
    ip route get 1 2>/dev/null | awk '{print $7; exit}'
}
