;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.config
  "EDN config loader using Aero."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn load-config
  "Load configuration from EDN file.
   Checks SMITHR_CONFIG env var first, then classpath default."
  ([]
   (load-config (or (System/getenv "SMITHR_CONFIG")
                    (io/resource "smithr.edn"))))
  ([source]
   (log/info "Loading config from" (str source))
   (let [config (aero/read-config source)]
     (log/info "Config loaded:" (pr-str (update config :hosts
                                                 (fn [hosts]
                                                   (mapv #(select-keys % [:label]) hosts)))))
     config)))
