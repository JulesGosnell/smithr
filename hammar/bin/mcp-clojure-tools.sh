#!/bin/sh
cd "$(dirname "$0")/.."
MCP_CP=$(clojure -Sdeps '{:deps {io.github.bhauman/clojure-mcp {:git/tag "v0.2.3" :git/sha "bbefc7a"}}}' -Spath)
HAMMAR_CP=$(clojure -Spath -M:dev)
java -cp "$HAMMAR_CP:$MCP_CP" clojure.main -m hammar.clojure-mcp
