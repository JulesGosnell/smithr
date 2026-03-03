#!/usr/bin/env bash
set -euo pipefail

# Run a Playwright spec file inside the sidecar container.
#
# Usage:
#   docker exec -e EMAIL=... -e PASSWORD=... <container> \
#     /usr/local/bin/run-playwright.sh <spec-file> [extra-args...]
#
# The spec file path is relative to /app/apps/web/ (e.g. tests/e2e/setup-profile.spec.ts).
# Environment variables (EMAIL, PASSWORD, ORG_NAME, etc.) are passed through
# to the Playwright process.

SPEC="${1:?Usage: run-playwright.sh <spec-file> [extra-args...]}"
shift

cd /app/apps/web

exec pnpm exec playwright test "$SPEC" --project=chromium "$@"
