(ns hammar.lease
  "Lease acquire/release/GC logic.
   Uses swap! on state atom for atomic compare-and-set semantics.
   Manages SSH tunnels: created on acquire, destroyed on release/GC."
  (:require [clojure.tools.logging :as log]
            [hammar.state :as state])
  (:import [java.time Instant Duration]
           [java.util UUID]))

;; ---------------------------------------------------------------------------
;; SSH tunnel management
;; ---------------------------------------------------------------------------

(defonce ^:private tunnels
  (atom {}))  ;; lease-id -> {:process Process, :port int}

(defonce ^:private next-tunnel-port
  (atom 17000))

(defn- allocate-tunnel-port!
  "Allocate the next available tunnel port."
  []
  (let [port (swap! next-tunnel-port inc)]
    (dec port)))

(defn- start-tunnel!
  "Start an SSH tunnel for a lease. Returns tunnel info map."
  [lease-id resource]
  (let [{:keys [ssh-host ssh-port adb-host adb-port]} (:connection resource)
        tunnel-port (allocate-tunnel-port!)]
    (log/info "Starting tunnel on port" tunnel-port "for lease" lease-id
              "-> resource" (:id resource))
    ;; For now, record the tunnel port. Actual tunnel processes will
    ;; be platform-specific:
    ;; - Android: socat TCP-LISTEN:tunnel-port,fork TCP:adb-host:adb-port
    ;; - iOS: SSH tunnel through container to macOS VM
    (let [tunnel-info {:port tunnel-port
                       :resource-id (:id resource)
                       :started-at (Instant/now)}]
      (swap! tunnels assoc lease-id tunnel-info)
      tunnel-info)))

(defn- stop-tunnel!
  "Stop and clean up an SSH tunnel for a lease."
  [lease-id]
  (when-let [tunnel (get @tunnels lease-id)]
    (log/info "Stopping tunnel on port" (:port tunnel) "for lease" lease-id)
    ;; Kill the tunnel process if running
    (when-let [proc (:process tunnel)]
      (try (.destroyForcibly proc)
           (catch Exception e
             (log/warn "Error stopping tunnel process:" (.getMessage e)))))
    (swap! tunnels dissoc lease-id)))

;; ---------------------------------------------------------------------------
;; Lease acquire
;; ---------------------------------------------------------------------------

(defn acquire!
  "Atomically acquire a lease on an available resource.
   Returns the lease map on success, nil if no resource available.
   Also starts an SSH tunnel for the lease."
  [{:keys [type platform ttl-seconds lessee]
    :or   {ttl-seconds 1800
           lessee      "anonymous"}}]
  (let [lease-id    (str (UUID/randomUUID))
        now         (Instant/now)
        expires-at  (.plus now (Duration/ofSeconds ttl-seconds))
        result      (atom nil)]
    ;; Atomic swap: find first warm resource and mark it leased
    (swap! state/state
           (fn [s]
             (let [candidates (->> (vals (:resources s))
                                   (filter #(and (= (:status %) :warm)
                                                 (= (:type %) (keyword type))
                                                 (= (:platform %) (keyword platform))))
                                   (sort-by :id))]
               (if-let [resource (first candidates)]
                 (let [lease {:id          lease-id
                              :resource-id (:id resource)
                              :host        (:host resource)
                              :lessee      lessee
                              :ttl-seconds ttl-seconds
                              :acquired-at now
                              :expires-at  expires-at}]
                   (reset! result {:lease lease :resource resource})
                   (-> s
                       (assoc-in [:resources (:id resource) :status] :leased)
                       (assoc-in [:resources (:id resource) :lease-id] lease-id)
                       (assoc-in [:resources (:id resource) :updated-at] now)
                       (assoc-in [:leases lease-id] lease)))
                 ;; No resource available
                 s))))
    (when-let [{:keys [lease resource]} @result]
      ;; Start SSH tunnel outside the atom swap
      (let [tunnel (start-tunnel! lease-id resource)]
        ;; Update lease with tunnel connection info
        (swap! state/state
               assoc-in [:leases lease-id :connection]
               {:tunnel-port (:port tunnel)})
        (log/info "Lease acquired:" lease-id "resource:" (:id resource)
                  "lessee:" lessee "ttl:" ttl-seconds "s"
                  "tunnel-port:" (:port tunnel))
        (assoc lease :connection {:tunnel-port (:port tunnel)})))))

;; ---------------------------------------------------------------------------
;; Lease release
;; ---------------------------------------------------------------------------

(defn release!
  "Release a lease: mark resource warm, remove lease, stop tunnel.
   Returns true if lease was found and released."
  [lease-id]
  (let [released? (atom false)]
    (swap! state/state
           (fn [s]
             (if-let [lease (get-in s [:leases lease-id])]
               (do
                 (reset! released? true)
                 (-> s
                     (assoc-in [:resources (:resource-id lease) :status] :warm)
                     (update-in [:resources (:resource-id lease)] dissoc :lease-id)
                     (assoc-in [:resources (:resource-id lease) :updated-at] (Instant/now))
                     (update :leases dissoc lease-id)))
               s)))
    (when @released?
      (stop-tunnel! lease-id)
      (log/info "Lease released:" lease-id))
    @released?))

;; ---------------------------------------------------------------------------
;; Garbage collection
;; ---------------------------------------------------------------------------

(defn gc-expired-leases!
  "Reap expired leases. Only GCs resources on own-host if specified."
  ([]
   (gc-expired-leases! nil))
  ([own-host]
   (let [now     (Instant/now)
         expired (->> (state/leases)
                      (filter #(.isAfter now (:expires-at %)))
                      (filter #(or (nil? own-host)
                                   (= own-host (:host %)))))]
     (doseq [lease expired]
       (log/info "GC: expiring lease" (:id lease)
                 "resource:" (:resource-id lease)
                 "lessee:" (:lessee lease))
       (release! (:id lease)))
     (count expired))))

(defn start-gc-loop!
  "Start the GC background thread. Returns a future for cancellation."
  [interval-seconds own-host]
  (log/info "Starting GC loop every" interval-seconds "seconds"
            (if own-host (str "(host: " own-host ")") "(all hosts)"))
  (future
    (while (not (Thread/interrupted))
      (try
        (let [n (gc-expired-leases! own-host)]
          (when (pos? n)
            (log/info "GC reaped" n "expired leases")))
        (catch InterruptedException _
          (log/info "GC loop interrupted, exiting")
          (.interrupt (Thread/currentThread)))
        (catch Exception e
          (log/error e "Error in GC loop")))
      (Thread/sleep (* interval-seconds 1000)))))
