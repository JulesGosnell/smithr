;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.metrics
  "Resource monitoring via Prometheus node_exporter.
   Scrapes node_exporter endpoints on managed resources every 4s,
   computes CPU/MEM/DISK utilisation, and maintains a ring buffer
   of the last 30 samples for sparkline rendering on the dashboard."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [smithr.state :as state])
  (:import [java.net HttpURLConnection URL]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; History atom — separate from main state (high frequency, transient)
;; ---------------------------------------------------------------------------

(defonce history
  (atom {}))
;; resource-id -> {:cpu [0.12 0.15 ...] :mem [0.47 ...] :disk [0.23 ...]
;;                 :cpu_cores 16 :mem_total_bytes 25769803776 :disk_total_bytes 128849018880
;;                 :prev-cpu-counters {...} :last-scrape <instant> :scrape-ok true}

(def ^:private ring-size 30)

(defn- ring-push
  "Append val to a vector, keeping at most ring-size elements."
  [v val]
  (let [v (conj (or v []) val)]
    (if (> (count v) ring-size)
      (subvec v (- (count v) ring-size))
      v)))

;; ---------------------------------------------------------------------------
;; Prometheus text format parser
;; ---------------------------------------------------------------------------

(defn parse-prometheus
  "Parse Prometheus text exposition format into a seq of maps.
   Each map: {:name str :labels {str str} :value double}
   Skips comment lines (#) and empty lines."
  [text]
  (when text
    (->> (str/split-lines text)
         (remove #(or (str/blank? %) (str/starts-with? % "#")))
         (keep (fn [line]
                 (when-let [[_ name labels-str value-str]
                            (re-matches #"(\w+)(?:\{([^}]*)\})?\s+([\d.eE+\-]+(?:NaN|Inf)?)" line)]
                   (let [labels (when (seq labels-str)
                                  (into {}
                                        (map (fn [pair]
                                               (let [[k v] (str/split pair #"=" 2)]
                                                 [k (str/replace (or v "") "\"" "")])))
                                        (str/split labels-str #",")))
                         value (try (Double/parseDouble value-str)
                                    (catch NumberFormatException _ nil))]
                     (when value
                       {:name name :labels (or labels {}) :value value}))))))))

;; ---------------------------------------------------------------------------
;; HTTP scraper
;; ---------------------------------------------------------------------------

(defn scrape
  "Scrape a node_exporter /metrics endpoint. Returns parsed metrics or nil.
   Uses a 2s connect + 2s read timeout."
  [url]
  (try
    (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
                 (.setConnectTimeout 2000)
                 (.setReadTimeout 2000)
                 (.setRequestMethod "GET"))
          status (.getResponseCode conn)]
      (if (= 200 status)
        (let [body (slurp (.getInputStream conn))]
          (parse-prometheus body))
        (do (log/debug "Scrape" url "returned status" status)
            nil)))
    (catch Exception e
      (log/debug "Scrape failed for" url ":" (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; Metric computation
;; ---------------------------------------------------------------------------

(defn- extract-cpu-counters
  "Extract per-CPU idle seconds from node_cpu_seconds_total metrics.
   Returns {cpu-label {:idle seconds, :total seconds}}."
  [metrics]
  (let [cpu-metrics (filter #(= (:name %) "node_cpu_seconds_total") metrics)]
    (reduce (fn [acc m]
              (let [cpu (get (:labels m) "cpu")
                    mode (get (:labels m) "mode")]
                (-> acc
                    (update-in [cpu :total] (fnil + 0.0) (:value m))
                    (cond->
                      (= mode "idle")
                      (update-in [cpu :idle] (fnil + 0.0) (:value m))))))
            {}
            cpu-metrics)))

(defn- compute-cpu-pct
  "Compute overall CPU utilisation from two scrapes of cpu counters.
   Returns a fraction 0.0-1.0 or nil if insufficient data."
  [prev-counters curr-counters]
  (when (and (seq prev-counters) (seq curr-counters))
    (let [cpus (keys curr-counters)
          deltas (keep (fn [cpu]
                         (let [prev (get prev-counters cpu)
                               curr (get curr-counters cpu)]
                           (when (and prev curr)
                             (let [dt (- (:total curr) (:total prev))
                                   di (- (:idle curr 0) (:idle prev 0))]
                               (when (pos? dt)
                                 (- 1.0 (/ di dt)))))))
                       cpus)]
      (when (seq deltas)
        (/ (reduce + deltas) (count deltas))))))

(defn- find-metric
  "Find a metric by name, optionally filtering labels."
  ([metrics name]
   (first (filter #(= (:name %) name) metrics)))
  ([metrics name label-key label-val]
   (first (filter #(and (= (:name %) name)
                        (= (get (:labels %) label-key) label-val))
                  metrics))))

(defn- find-all-metrics
  "Find all metrics matching a name."
  [metrics name]
  (filter #(= (:name %) name) metrics))

(defn- compute-mem
  "Compute memory utilisation. Handles both Linux and macOS metric names.
   Returns {:pct fraction, :total-bytes long} or nil."
  [metrics]
  ;; Linux: node_memory_MemTotal_bytes, node_memory_MemAvailable_bytes
  ;; macOS (node_exporter): same names
  (let [total (or (some-> (find-metric metrics "node_memory_MemTotal_bytes") :value)
                  ;; Fallback to node_memory_total_bytes (macOS node_exporter)
                  (some-> (find-metric metrics "node_memory_total_bytes") :value))
        avail (or (some-> (find-metric metrics "node_memory_MemAvailable_bytes") :value)
                  ;; macOS fallback: total - (active + wired)
                  (when total
                    (let [active (some-> (find-metric metrics "node_memory_active_bytes") :value)
                          wired (some-> (find-metric metrics "node_memory_wired_bytes") :value)]
                      (when (and active wired)
                        (- total active wired)))))]
    (when (and total avail (pos? total))
      {:pct (- 1.0 (/ avail total))
       :total-bytes (long total)})))

(defn- home-mountpoint?
  "Check if a mountpoint is a home directory filesystem."
  [mp]
  (or (= mp "/home")
      (str/starts-with? (or mp "") "/home/")
      (= mp "/Users")
      (str/starts-with? (or mp "") "/Users/")
      (= mp "/Volumes/BuildHomes")))

(defn- compute-disk
  "Compute disk utilisation for the home mountpoint.
   Falls back to / then to the largest filesystem if no home mount found.
   Returns {:pct fraction, :total-bytes long} or nil."
  [metrics]
  (let [size-metrics (find-all-metrics metrics "node_filesystem_size_bytes")
        avail-metrics (find-all-metrics metrics "node_filesystem_avail_bytes")
        ;; Build map: mountpoint -> {:size :avail}
        fs-map (reduce (fn [acc m]
                         (let [mp (get (:labels m) "mountpoint")]
                           (assoc-in acc [mp :size] (:value m))))
                       {} size-metrics)
        fs-map (reduce (fn [acc m]
                         (let [mp (get (:labels m) "mountpoint")]
                           (assoc-in acc [mp :avail] (:value m))))
                       fs-map avail-metrics)
        ;; Prefer home mountpoint, fallback to /, then largest filesystem
        mp-key (or (first (filter home-mountpoint? (keys fs-map)))
                   (when (contains? fs-map "/") "/")
                   ;; Fallback: pick the largest filesystem (by size)
                   (->> fs-map
                        (filter (fn [[_ v]] (and (:size v) (:avail v))))
                        (sort-by (fn [[_ v]] (:size v)) >)
                        ffirst))
        fs (get fs-map mp-key)]
    (when (and fs (:size fs) (:avail fs) (pos? (:size fs)))
      {:pct (- 1.0 (/ (:avail fs) (:size fs)))
       :total-bytes (long (:size fs))})))

;; ---------------------------------------------------------------------------
;; Endpoint resolution
;; ---------------------------------------------------------------------------

(defn- metrics-url
  "Resolve node_exporter URL for a resource."
  [resource]
  (let [{:keys [connection]} resource
        platform (:platform resource)
        ;; Use metrics-port from connection if available, otherwise defaults
        port (or (:metrics-port connection)
                 (case platform
                   :macos 10100   ;; QEMU port-forwarded
                   9100))         ;; default node_exporter port
        ;; For remote hosts, use host-address + host-mapped port
        ;; For local Docker containers, use container IP
        host (or (:ssh-host connection)
                 (:adb-host connection)
                 "localhost")]
    (str "http://" host ":" port "/metrics")))

;; ---------------------------------------------------------------------------
;; Scrape and update
;; ---------------------------------------------------------------------------

(defn- scrape-resource!
  "Scrape a single resource's node_exporter and update history."
  [resource]
  (let [url (metrics-url resource)
        rid (:id resource)
        metrics (scrape url)]
    (if metrics
      (let [cpu-counters (extract-cpu-counters metrics)
            prev-counters (get-in @history [rid :prev-cpu-counters])
            cpu-pct (compute-cpu-pct prev-counters cpu-counters)
            cpu-cores (count cpu-counters)
            mem (compute-mem metrics)
            disk (compute-disk metrics)]
        (swap! history
               (fn [h]
                 (let [entry (get h rid {})]
                   (assoc h rid
                          (cond-> entry
                            ;; Always update counters for next delta
                            true (assoc :prev-cpu-counters cpu-counters
                                        :last-scrape (Instant/now)
                                        :scrape-ok true)
                            ;; CPU: only push when we have a delta (need 2 samples)
                            cpu-pct (update :cpu ring-push cpu-pct)
                            cpu-cores (assoc :cpu-cores cpu-cores)
                            ;; Memory
                            (:pct mem) (update :mem ring-push (:pct mem))
                            (:total-bytes mem) (assoc :mem-total-bytes (:total-bytes mem))
                            ;; Disk
                            (:pct disk) (update :disk ring-push (:pct disk))
                            (:total-bytes disk) (assoc :disk-total-bytes (:total-bytes disk))))))))
      ;; Scrape failed — mark as failed but keep existing history
      (swap! history assoc-in [rid :scrape-ok] false))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn metrics-snapshot
  "Return a map of resource-id -> metrics data for the API.
   Only includes resources that have been successfully scraped."
  []
  (into {}
        (keep (fn [[rid entry]]
                (when (:scrape-ok entry)
                  [rid {:cpu (:cpu entry [])
                        :mem (:mem entry [])
                        :disk (:disk entry [])
                        :cpu_cores (:cpu-cores entry)
                        :mem_total_gb (when-let [b (:mem-total-bytes entry)]
                                        (Math/round (/ b 1073741824.0)))
                        :disk_total_gb (when-let [b (:disk-total-bytes entry)]
                                         (Math/round (/ b 1073741824.0)))
                        :cpu_current (some-> (:cpu entry) last)
                        :mem_current (some-> (:mem entry) last)
                        :disk_current (some-> (:disk entry) last)}])))
        @history))

(defn scrape-all!
  "Scrape all warm/leased/shared resources."
  []
  (let [resources (state/resources)]
    (doseq [r resources]
      (when (#{:warm :leased :shared :booting} (:status r))
        (try
          (scrape-resource! r)
          (catch Exception e
            (log/debug "Scrape error for" (:id r) ":" (.getMessage e))))))))

(defn start-scrape-loop!
  "Start the metrics scrape loop. Runs every interval-ms.
   Returns a future for cancellation."
  [interval-ms]
  (log/info "Starting metrics scrape loop every" (/ interval-ms 1000) "seconds")
  (future
    (while (not (Thread/interrupted))
      (try
        (scrape-all!)
        (catch InterruptedException _
          (log/info "Metrics scrape loop interrupted, exiting")
          (.interrupt (Thread/currentThread)))
        (catch Exception e
          (log/error e "Error in metrics scrape loop")))
      (Thread/sleep interval-ms))))
