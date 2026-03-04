#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
#
# list-build-users.sh — List active build user accounts on the macOS VM
#
# Installed at /Users/smithr/bin/list-build-users.sh on macOS VMs.
# Called by Smithr (smithr.macos) via SSH as smithr.
#
# Output: One username per line, sorted alphabetically.
#
# Usage:
#   list-build-users.sh

set -euo pipefail

ls /Users/ 2>/dev/null | grep -E '^build-|^[a-z].*-build' | sort
