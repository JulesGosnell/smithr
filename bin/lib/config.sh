#!/usr/bin/env bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# smithr/bin/lib/config.sh — YAML config parser for smithr.yml
#
# Reads a project's smithr.yml and makes values available via shell functions.
# Uses Python3 + PyYAML to parse YAML → JSON, then jq to query.
#
# Usage:
#   source bin/lib/config.sh
#   smithr_config_load ./smithr.yml      # Parse and cache
#   smithr_config ".server.compose[0]"   # Query a value
#   smithr_config_arr ".server.setup"    # Query an array
#
# Sourced by other scripts. Do not execute directly.

# Cached JSON representation of the loaded config
_SMITHR_CONFIG_JSON=""
_SMITHR_CONFIG_DIR=""

# Parse YAML file to JSON using Python3
_yaml_to_json() {
    python3 -c "
import sys, json, yaml
with open(sys.argv[1]) as f:
    data = yaml.safe_load(f)
json.dump(data, sys.stdout)
" "$1"
}

# Load and cache a smithr.yml config file
# Sets _SMITHR_CONFIG_JSON and _SMITHR_CONFIG_DIR
smithr_config_load() {
    local config_path="$1"

    [[ -f "$config_path" ]] || return 1

    _SMITHR_CONFIG_JSON=$(_yaml_to_json "$config_path") || {
        log_error "Failed to parse: $config_path"
        return 1
    }

    _SMITHR_CONFIG_DIR="$(cd "$(dirname "$config_path")" && pwd)"
    export SMITHR_CONFIG_FILE="$config_path"
    export SMITHR_CONFIG_DIR="$_SMITHR_CONFIG_DIR"
}

# Query a single value from the loaded config
# Returns "null" (the string) if key doesn't exist
# Usage: smithr_config ".project.name"
smithr_config() {
    local jq_path="$1"
    local default="${2:-}"

    if [[ -z "$_SMITHR_CONFIG_JSON" ]]; then
        echo "$default"
        return
    fi

    local val
    val=$(echo "$_SMITHR_CONFIG_JSON" | jq -r "$jq_path // empty" 2>/dev/null)

    if [[ -z "$val" ]]; then
        echo "$default"
    else
        echo "$val"
    fi
}

# Query an array from the loaded config, one element per line
# Usage: smithr_config_arr ".server.setup"
smithr_config_arr() {
    local jq_path="$1"

    [[ -n "$_SMITHR_CONFIG_JSON" ]] || return 0

    echo "$_SMITHR_CONFIG_JSON" | jq -r "($jq_path // []) | .[]" 2>/dev/null
}

# Query a JSON object from the loaded config
# Usage: smithr_config_obj ".server.env"
smithr_config_obj() {
    local jq_path="$1"

    [[ -n "$_SMITHR_CONFIG_JSON" ]] || return 0

    echo "$_SMITHR_CONFIG_JSON" | jq -c "$jq_path // {}" 2>/dev/null
}

# Check if a config key exists and is non-null
# Usage: if smithr_config_has ".server.image.load"; then ...
smithr_config_has() {
    local jq_path="$1"

    [[ -n "$_SMITHR_CONFIG_JSON" ]] || return 1

    local val
    val=$(echo "$_SMITHR_CONFIG_JSON" | jq -e "$jq_path // empty" 2>/dev/null)
    [[ -n "$val" ]]
}

# Export all key-value pairs from a config object as environment variables
# Usage: smithr_config_export_env ".server.env"
smithr_config_export_env() {
    local jq_path="$1"

    [[ -n "$_SMITHR_CONFIG_JSON" ]] || return 0

    local pairs
    pairs=$(echo "$_SMITHR_CONFIG_JSON" | jq -r "($jq_path // {}) | to_entries[] | \"\(.key)=\(.value)\"" 2>/dev/null) || return 0

    while IFS= read -r pair; do
        [[ -n "$pair" ]] || continue
        local key="${pair%%=*}"
        local val="${pair#*=}"
        # Expand shell variables in the value
        val=$(eval echo "$val" 2>/dev/null || echo "$val")
        export "$key=$val"
    done <<< "$pairs"
}

# Resolve a path relative to the config file directory
# Usage: smithr_config_path ".mobile.android.apk"
smithr_config_path() {
    local jq_path="$1"
    local val
    val=$(smithr_config "$jq_path")

    [[ -n "$val" ]] || return 1

    # If already absolute, return as-is
    if [[ "$val" == /* ]]; then
        echo "$val"
    else
        echo "${_SMITHR_CONFIG_DIR}/${val}"
    fi
}
