(ns hammar.lease
  "Lease acquire/release/GC logic.
   Uses swap! on state atom for atomic compare-and-set semantics.
   Manages SSH tunnels: created on acquire, destroyed on release/GC."
  (:require [clojure.tools.logging :as log]
            [hammar.state :as state]
            [hammar.macos :as macos])
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

(declare release!)

(defn acquire!
  "Atomically acquire a lease on an available resource.
   Returns the lease map on success, nil if no resource available.

   :lease-type can be :build (shared, concurrent access to macOS VM)
   or :phone (exclusive access, default for backwards compat).

   Build leases create a per-user macOS account and SSH tunnel.
   Phone leases get exclusive VM access (same as legacy behavior)."
  [{:keys [type platform ttl-seconds lessee lease-type]
    :or   {ttl-seconds 1800
           lessee      "anonymous"
           lease-type  :phone}}]
  (let [lease-id    (str (UUID/randomUUID))
        now         (Instant/now)
        expires-at  (.plus now (Duration/ofSeconds ttl-seconds))
        lease-type  (keyword lease-type)
        result      (atom nil)]
    ;; Atomic swap: find resource and update state
    (swap! state/state
           (fn [s]
             (let [candidates (if (= lease-type :build)
                                ;; Build: warm or shared with capacity
                                (->> (vals (:resources s))
                                     (filter #(and (= (:type %) :vm)
                                                   (= (:platform %) (keyword platform))
                                                   (or (= (:status %) :warm)
                                                       (and (= (:status %) :shared)
                                                            (< (count (:active-leases % #{}))
                                                               (:max-slots % 10))))))
                                     (sort-by :id))
                                ;; Phone: warm only (exclusive)
                                (->> (vals (:resources s))
                                     (filter #(and (= (:status %) :warm)
                                                   (= (:type %) (keyword type))
                                                   (= (:platform %) (keyword platform))))
                                     (sort-by :id)))]
               (if-let [resource (first candidates)]
                 (let [lease {:id          lease-id
                              :resource-id (:id resource)
                              :host        (:host resource)
                              :lessee      lessee
                              :lease-type  lease-type
                              :ttl-seconds ttl-seconds
                              :acquired-at now
                              :expires-at  expires-at}]
                   (reset! result {:lease lease :resource resource})
                   (if (= lease-type :build)
                     ;; Build lease: add to active-leases, mark shared
                     (-> s
                         (update-in [:resources (:id resource) :active-leases]
                                    (fnil conj #{}) lease-id)
                         (assoc-in [:resources (:id resource) :status] :shared)
                         (assoc-in [:resources (:id resource) :updated-at] now)
                         (assoc-in [:leases lease-id] lease))
                     ;; Phone lease: exclusive, mark leased (legacy behavior)
                     (-> s
                         (assoc-in [:resources (:id resource) :status] :leased)
                         (assoc-in [:resources (:id resource) :lease-id] lease-id)
                         (assoc-in [:resources (:id resource) :updated-at] now)
                         (assoc-in [:leases lease-id] lease))))
                 ;; No resource available
                 s))))
    (when-let [{:keys [lease resource]} @result]
      (if (= lease-type :build)
        ;; Build lease: create macOS user, then start tunnel
        (if-let [user-info (macos/create-user! resource lease-id)]
          (let [tunnel (start-tunnel! lease-id resource)
                {:keys [ssh-host ssh-port]} (:connection resource)
                connection {:tunnel-port (:port tunnel)
                            :ssh-user    (:macos-user user-info)
                            :ssh-host    ssh-host
                            :ssh-port    (or ssh-port 10022)
                            :home-dir    (:home-dir user-info)}]
            ;; Update lease with connection and user info
            (swap! state/state
                   (fn [s]
                     (-> s
                         (assoc-in [:leases lease-id :connection] connection)
                         (assoc-in [:leases lease-id :macos-user] (:macos-user user-info)))))
            (log/info "Build lease acquired:" lease-id "resource:" (:id resource)
                      "lessee:" lessee "user:" (:macos-user user-info)
                      "tunnel-port:" (:port tunnel))
            (assoc lease
                   :connection connection
                   :macos-user (:macos-user user-info)))
          ;; User creation failed — rollback
          (do
            (log/error "Failed to create macOS user for lease" lease-id "- rolling back")
            (release! lease-id)
            nil))
        ;; Phone lease: start tunnel (legacy behavior)
        (let [tunnel (start-tunnel! lease-id resource)]
          (swap! state/state
                 assoc-in [:leases lease-id :connection]
                 {:tunnel-port (:port tunnel)})
          (log/info "Phone lease acquired:" lease-id "resource:" (:id resource)
                    "lessee:" lessee "ttl:" ttl-seconds "s"
                    "tunnel-port:" (:port tunnel))
          (assoc lease :connection {:tunnel-port (:port tunnel)}))))))

;; ---------------------------------------------------------------------------
;; Lease release
;; ---------------------------------------------------------------------------

(defn release!
  "Release a lease: update resource state, remove lease, stop tunnel.
   For build leases: removes from active-leases, deletes macOS user.
   For phone leases: marks resource warm (legacy behavior).
   Returns true if lease was found and released."
  [lease-id]
  (let [released-lease (atom nil)]
    (swap! state/state
           (fn [s]
             (if-let [lease (get-in s [:leases lease-id])]
               (let [resource-id (:resource-id lease)
                     build? (= (:lease-type lease) :build)]
                 (reset! released-lease lease)
                 (if build?
                   ;; Build lease: remove from active-leases
                   (let [new-active (disj (get-in s [:resources resource-id :active-leases] #{})
                                          lease-id)
                         new-status (if (empty? new-active) :warm :shared)]
                     (-> s
                         (assoc-in [:resources resource-id :active-leases] new-active)
                         (assoc-in [:resources resource-id :status] new-status)
                         (assoc-in [:resources resource-id :updated-at] (Instant/now))
                         (update :leases dissoc lease-id)))
                   ;; Phone lease: mark warm (legacy)
                   (-> s
                       (assoc-in [:resources resource-id :status] :warm)
                       (update-in [:resources resource-id] dissoc :lease-id)
                       (assoc-in [:resources resource-id :updated-at] (Instant/now))
                       (update :leases dissoc lease-id))))
               s)))
    (when-let [lease @released-lease]
      ;; Clean up outside the atom swap
      (when (= (:lease-type lease) :build)
        (when-let [macos-user (:macos-user lease)]
          (let [resource (state/resource (:resource-id lease))]
            (when resource
              (future
                (try
                  (macos/delete-user! resource macos-user)
                  (catch Exception e
                    (log/warn "Failed to delete macOS user" macos-user ":" (.getMessage e)))))))))
      (stop-tunnel! lease-id)
      (log/info "Lease released:" lease-id
                (when (= (:lease-type lease) :build)
                  (str "(build user: " (:macos-user lease) ")"))))
    (boolean @released-lease)))

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
