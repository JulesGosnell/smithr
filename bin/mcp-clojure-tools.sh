#!/bin/sh
cd "$(dirname "$0")/.."
MCP_CP=$(clojure -Sdeps '{:deps {io.github.bhauman/clojure-mcp {:git/tag "v0.2.3" :git/sha "bbefc7a"}}}' -Spath)
SMITHR_CP=$(clojure -Spath -M:dev)
java -cp "$SMITHR_CP:$MCP_CP" clojure.main -m smithr.clojure-mcp
