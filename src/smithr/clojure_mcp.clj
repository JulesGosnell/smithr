;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.clojure-mcp
  (:require [nrepl.server :refer [start-server] :rename {start-server start-nrepl-server}]))

(defn -main []
  (let [{p :port} (start-nrepl-server)]
    (println "connecting clojure-mcp to nrepl on port:" p)
    ((requiring-resolve 'clojure-mcp.main/start-mcp-server) {:port p})))
