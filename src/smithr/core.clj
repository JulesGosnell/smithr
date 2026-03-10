;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.core
  "Entry point for Smithr resource pool manager.
   Starts Docker event subscriptions, GC loop, and HTTP server."
  (:require [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty]
            [smithr.config :as config]
            [smithr.docker :as docker]
            [smithr.lease :as lease]
            [smithr.metrics :as metrics]
            [smithr.provision :as provision]
            [smithr.devices :as devices]
            [smithr.templates :as templates]
            [smithr.store.disk :as disk-store]
            [smithr.api :as api])
  (:gen-class))

(defonce ^:private system (atom nil))

(defn start!
  "Start the Smithr system: connect to Docker hosts, start GC, start HTTP server."
  [config]
  (log/info "Starting Smithr resource pool manager")

  ;; Initialize store backends (NFS-backed for production)
  (let [dist-lock (disk-store/create-lock "/srv/shared/smithr/leases")
        template-kv (disk-store/create-kv "/srv/shared/smithr/templates")]
    (lease/init-lock! dist-lock)
    (templates/init-kv! template-kv))

  ;; Clean up stale tunnels and shared locks from previous sessions
  (lease/cleanup-stale-tunnels!)
  (lease/cleanup-stale-shared-locks!)

  ;; Initialize provisioning config (opt-in — no-op if absent)
  (provision/set-config! config)
  (when (:provisioning config)
    (log/info "Provisioning enabled — lazy resource provisioning active"))

  ;; Load dynamic templates from shared storage
  (templates/load-templates!)

  ;; Connect to Docker hosts
  (let [network-name (get-in config [:compose :network] "smithr-network")
        host-connections (doall
                          (for [host-config (:hosts config)]
                            (docker/connect-host! host-config network-name)))
        connected (filterv some? host-connections)
        own-host (get-in config [:gc :own-host])]
    (log/info "Connected to" (count connected) "of" (count (:hosts config)) "Docker hosts")

    ;; Initial device scan
    (when own-host
      (try
        (let [n (devices/register-devices! own-host)]
          (when (pos? n)
            (log/info "Discovered" n "physical devices")))
        (catch Exception e
          (log/debug "Initial device scan skipped:" (.getMessage e)))))

    ;; Start GC loop — each instance GCs all its own leases (state is per-instance)
    (let [gc-interval (get-in config [:gc :interval-seconds] 30)
          gc-future   (lease/start-gc-loop! gc-interval nil
                        :idle-timeout (get-in config [:provisioning :idle-timeout-seconds])
                        :device-host own-host)
          ;; Start metrics scrape loop (every 4s)
          metrics-future (metrics/start-scrape-loop! 4000)]

      ;; Start HTTP server
      (let [port    (get-in config [:server :port] 7070)
            host    (get-in config [:server :host] "0.0.0.0")
            handler (api/app)
            server  (jetty/run-jetty handler {:port  port
                                              :host  host
                                              :join? false})]
        (log/info "Smithr listening on" (str host ":" port))
        (reset! system {:config         config
                        :connections    connected
                        :gc-future      gc-future
                        :metrics-future metrics-future
                        :server         server})
        server))))

(defn stop!
  "Stop the Smithr system gracefully."
  []
  (when-let [sys @system]
    (log/info "Shutting down Smithr")
    ;; Stop HTTP server
    (when-let [server (:server sys)]
      (.stop server))
    ;; Cancel GC loop
    (when-let [gc (:gc-future sys)]
      (future-cancel gc))
    ;; Cancel metrics scrape loop
    (when-let [mf (:metrics-future sys)]
      (future-cancel mf))
    ;; Shutdown device bridges (stops socat/iproxy, removes marker containers)
    (try (devices/shutdown-bridges!)
         (catch Exception e
           (log/debug "Device bridge shutdown:" (.getMessage e))))
    ;; Close Docker event subscriptions
    (doseq [conn (:connections sys)]
      (when-let [sub (:subscription conn)]
        (try (.close sub) (catch Exception _)))
      (when-let [client (:client conn)]
        (try (.close client) (catch Exception _))))
    (reset! system nil)
    (log/info "Smithr stopped")))

(defn -main
  "Main entry point."
  [& _args]
  (let [config (config/load-config)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable stop!))
    (start! config)
    ;; Block main thread
    @(promise)))
