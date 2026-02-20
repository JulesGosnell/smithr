#!/bin/bash
set -e
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_PATH=$(cd "$SCRIPT_DIR/.." && pwd)
export PATH="$HOME/go/bin:$HOME/bin:$PATH"
exec mcp-language-server \
    --workspace "$PROJECT_PATH" \
    --lsp clojure-lsp
