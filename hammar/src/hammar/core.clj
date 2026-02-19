(ns hammar.core
  "Entry point for Hammar resource pool manager.
   Starts Docker event subscriptions, GC loop, and HTTP server."
  (:require [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty]
            [hammar.config :as config]
            [hammar.docker :as docker]
            [hammar.lease :as lease]
            [hammar.api :as api])
  (:gen-class))

(defonce ^:private system (atom nil))

(defn start!
  "Start the Hammar system: connect to Docker hosts, start GC, start HTTP server."
  [config]
  (log/info "Starting Smithr resource pool manager")

  ;; Clean up stale tunnels and shared locks from previous sessions
  (lease/cleanup-stale-tunnels!)
  (.mkdirs (java.io.File. "/srv/shared/smithr/leases"))
  (lease/cleanup-stale-shared-locks!)

  ;; Connect to Docker hosts
  (let [network-name (get-in config [:compose :network] "smithr-network")
        host-connections (doall
                          (for [host-config (:hosts config)]
                            (docker/connect-host! host-config network-name)))
        connected (filterv some? host-connections)]
    (log/info "Connected to" (count connected) "of" (count (:hosts config)) "Docker hosts")

    ;; Start GC loop — each instance GCs all its own leases (state is per-instance)
    (let [gc-interval (get-in config [:gc :interval-seconds] 30)
          gc-future   (lease/start-gc-loop! gc-interval nil)]

      ;; Start HTTP server
      (let [port    (get-in config [:server :port] 7070)
            host    (get-in config [:server :host] "0.0.0.0")
            handler (api/app)
            server  (jetty/run-jetty handler {:port  port
                                              :host  host
                                              :join? false})]
        (log/info "Smithr listening on" (str host ":" port))
        (reset! system {:config      config
                        :connections connected
                        :gc-future   gc-future
                        :server      server})
        server))))

(defn stop!
  "Stop the Hammar system gracefully."
  []
  (when-let [sys @system]
    (log/info "Shutting down Smithr")
    ;; Stop HTTP server
    (when-let [server (:server sys)]
      (.stop server))
    ;; Cancel GC loop
    (when-let [gc (:gc-future sys)]
      (future-cancel gc))
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
