(ns hammar.lease
  "Lease acquire/unlease/GC logic.
   Uses swap! on state atom for atomic compare-and-set semantics.
   Manages SSH tunnels: created on acquire, destroyed on unlease/GC."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :as shell]
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

(defn cleanup-stale-tunnels!
  "Kill orphaned SSH tunnel processes from previous Hammar sessions.
   Called on startup before accepting new leases."
  []
  (try
    (let [{:keys [exit]} (shell/sh "pkill" "-f" "ssh -N.*-L 170")]
      (if (zero? exit)
        (log/info "Cleaned up stale SSH tunnel processes")
        (log/debug "No stale SSH tunnels to clean up")))
    (catch Exception e
      (log/debug "No stale tunnels (pkill:" (.getMessage e) ")"))))

(defn- allocate-tunnel-port!
  "Allocate the next available tunnel port."
  []
  (let [port (swap! next-tunnel-port inc)]
    (dec port)))

(defn- docker-network-ip?
  "Is this a Docker network IP (10.x.x.x) reachable only from the local host?"
  [host]
  (and host (re-matches #"10\.\d+\.\d+\.\d+" host)))

(defn- resolve-tunnel-route
  "Given a target host:port, determine SSH -L forward and hop host.
   Docker network IPs → hop via localhost (same host can reach Docker network).
   Remote hostnames → hop via that host, forward to localhost on the remote."
  [target-host target-port]
  (if (docker-network-ip? target-host)
    {:fwd-host target-host :fwd-port target-port :hop "localhost"}
    {:fwd-host "localhost" :fwd-port target-port :hop (or target-host "localhost")}))

(defn- start-tunnel!
  "Start an SSH tunnel for a lease. Returns tunnel info map.
   Uses `ssh -N -L` for port forwarding. One SSH process per lease.
   Kill the process → client is disconnected."
  [lease-id resource]
  (let [{:keys [ssh-host ssh-port adb-host adb-port]} (:connection resource)
        tunnel-port (allocate-tunnel-port!)
        platform    (:platform resource)
        ;; iOS sidecar doesn't run SSH — route to parent macOS VM
        parent-conn (when (and (= platform :ios) (:parent resource))
                      (let [parent (first (filter #(= (:container %) (:parent resource))
                                                  (state/resources)))]
                        (when parent
                          (log/info "iOS tunnel: routing to parent" (:id parent)
                                    "at" (get-in parent [:connection :ssh-host]))
                          (:connection parent))))
        ;; Determine the target we need to reach
        [target-host target-port]
        (case platform
          :android [(or adb-host "localhost") (or adb-port 5555)]
          :macos   [(or ssh-host "localhost") (or ssh-port 10022)]
          :ios     (let [conn (or parent-conn (:connection resource))]
                     [(or (:ssh-host conn) "localhost") (or (:ssh-port conn) 10022)])
          ["localhost" 0])
        ;; Resolve forwarding route: Docker IPs hop via localhost, remote hops via hostname
        {:keys [fwd-host fwd-port hop]} (resolve-tunnel-route target-host target-port)
        target-str (str fwd-host ":" fwd-port " via " hop)
        cmd ["ssh" "-N"
             "-o" "StrictHostKeyChecking=no"
             "-o" "BatchMode=yes"
             "-o" "ExitOnForwardFailure=yes"
             "-o" "ServerAliveInterval=30"
             "-o" "ServerAliveCountMax=3"
             "-L" (str tunnel-port ":" fwd-host ":" fwd-port)
             hop]]
    (log/info "Starting SSH tunnel on port" tunnel-port "for lease" lease-id
              "→" target-str)
    (try
      (let [proc (-> (ProcessBuilder. ^java.util.List cmd)
                     (.redirectErrorStream true)
                     (.start))
            tunnel-info {:port        tunnel-port
                         :process     proc
                         :resource-id (:id resource)
                         :target      target-str
                         :started-at  (Instant/now)}]
        (swap! tunnels assoc lease-id tunnel-info)
        ;; Brief pause to let SSH establish the forwarding
        (Thread/sleep 500)
        (if (.isAlive proc)
          (log/info "SSH tunnel started: port" tunnel-port "→" target-str "pid" (.pid proc))
          (log/error "SSH tunnel exited immediately on port" tunnel-port
                     "— check SSH access to" hop))
        tunnel-info)
      (catch Exception e
        (log/error "Failed to start SSH tunnel on port" tunnel-port ":" (.getMessage e))
        (let [tunnel-info {:port        tunnel-port
                           :resource-id (:id resource)
                           :target      target-str
                           :started-at  (Instant/now)}]
          (swap! tunnels assoc lease-id tunnel-info)
          tunnel-info)))))

(defn- stop-tunnel!
  "Stop and clean up an SSH tunnel for a lease."
  [lease-id]
  (when-let [tunnel (get @tunnels lease-id)]
    (log/info "Stopping tunnel on port" (:port tunnel) "for lease" lease-id
              (when (:target tunnel) (str "→ " (:target tunnel))))
    ;; Kill the socat process if running
    (when-let [^Process proc (:process tunnel)]
      (try
        (when (.isAlive proc)
          (.destroyForcibly proc)
          (log/info "Tunnel process killed: pid" (.pid proc)))
        (catch Exception e
          (log/warn "Error stopping tunnel process:" (.getMessage e)))))
    (swap! tunnels dissoc lease-id)))

;; ---------------------------------------------------------------------------
;; Reverse port forwarding (server_ports: app → server connectivity)
;; ---------------------------------------------------------------------------

(defn- setup-server-ports!
  "Set up reverse port forwarding so the phone app can reach server ports.
   For Android: uses `adb reverse` (native ADB feature).
   For iOS: uses socat inside the macOS VM via SSH.
   Returns a map of {port {:status :forwarded/:failed}}."
  [lease-id resource tunnel-port server-ports]
  (let [platform (:platform resource)]
    (case platform
      :android
      (let [adb-target (str "localhost:" tunnel-port)]
        ;; Connect ADB through the tunnel first
        (log/info "Connecting ADB to" adb-target "for lease" lease-id)
        (let [{:keys [exit out]} (shell/sh "adb" "connect" adb-target)]
          (log/info "adb connect:" (clojure.string/trim (str out)))
          (when-not (zero? exit)
            (log/warn "adb connect failed, retrying in 3s...")
            (Thread/sleep 3000)
            (shell/sh "adb" "connect" adb-target)))
        ;; Small delay for connection to stabilize
        (Thread/sleep 1000)
        (into {}
              (for [port server-ports]
                (try
                  (log/info "Setting up adb reverse tcp:" port "for lease" lease-id)
                  (let [{:keys [exit out err]}
                        (shell/sh "adb" "-s" adb-target "reverse"
                                  (str "tcp:" port) (str "tcp:" port))]
                    (if (zero? exit)
                      (do (log/info "adb reverse tcp:" port "→ tcp:" port "OK")
                          [port {:status "forwarded" :phone-address (str "localhost:" port)}])
                      (do (log/warn "adb reverse failed for port" port ":" err)
                          [port {:status "failed" :error (str err)}])))
                  (catch Exception e
                    (log/warn "adb reverse exception for port" port ":" (.getMessage e))
                    [port {:status "failed" :error (.getMessage e)}])))))

      :ios
      (let [{:keys [ssh-host ssh-port]} (:connection resource)]
        (into {}
              (for [port server-ports]
                (try
                  (log/info "Setting up socat reverse port" port "in macOS VM for lease" lease-id)
                  (macos/ssh-exec-raw! resource
                    (str "nohup socat TCP-LISTEN:" port ",fork,reuseaddr TCP:10.0.2.2:" port
                         " > /dev/null 2>&1 & echo $!"))
                  (log/info "socat reverse tcp:" port "→ 10.0.2.2:" port "OK")
                  [port {:status "forwarded" :phone-address (str "localhost:" port)}]
                  (catch Exception e
                    (log/warn "socat reverse failed for port" port ":" (.getMessage e))
                    [port {:status "failed" :error (.getMessage e)}])))))

      ;; Unknown platform — no reverse forwarding
      (do
        (log/warn "Reverse port forwarding not supported for platform" platform)
        {}))))

(defn- teardown-server-ports!
  "Clean up reverse port forwarding on unlease."
  [resource tunnel-port server-ports]
  (let [platform (:platform resource)]
    (case platform
      :android
      (try
        (let [adb-target (str "localhost:" tunnel-port)]
          (shell/sh "adb" "-s" adb-target "reverse" "--remove-all")
          (shell/sh "adb" "disconnect" adb-target)
          (log/info "Cleared adb reverse ports and disconnected"))
        (catch Exception e
          (log/warn "Failed to clear adb reverse:" (.getMessage e))))

      :ios
      (try
        (doseq [port server-ports]
          (macos/ssh-exec-raw! resource
            (str "pkill -f 'socat TCP-LISTEN:" port "' 2>/dev/null || true")))
        (log/info "Cleared socat reverse ports in macOS VM")
        (catch Exception e
          (log/warn "Failed to clear socat reverse:" (.getMessage e))))

      nil)))

;; ---------------------------------------------------------------------------
;; Lease acquire
;; ---------------------------------------------------------------------------

(declare unlease!)

(defn- valid-workspace-name?
  "Validate workspace name: alphanumeric + hyphens, 3-31 chars, starts with letter."
  [name]
  (and (string? name)
       (re-matches #"[a-zA-Z][a-zA-Z0-9-]{2,30}" name)))

(defn acquire!
  "Atomically acquire a lease on an available resource.
   Returns the lease map on success, nil if no resource available.

   :lease-type can be :build (shared, concurrent access to macOS VM)
   or :phone (exclusive access, default for backwards compat).

   :workspace — optional named workspace for warm/persistent builds.
   If provided, the macOS user persists across leases (not deleted on unlease).

   Build leases create a per-user macOS account and SSH tunnel.
   Phone leases get exclusive VM access (same as legacy behavior)."
  [{:keys [type platform ttl-seconds lessee lease-type workspace server-ports]
    :or   {ttl-seconds 1800
           lessee      "anonymous"
           lease-type  :phone}}]
  ;; Validate workspace name if provided
  (when (and workspace (not (valid-workspace-name? workspace)))
    (log/warn "Invalid workspace name:" workspace)
    (throw (ex-info "Invalid workspace name" {:workspace workspace})))
  ;; Check if workspace is already leased
  (when workspace
    (when-let [ws (state/workspace workspace)]
      (when (= (:status ws) :leased)
        (log/warn "Workspace" workspace "is already leased by" (:lease-id ws))
        (throw (ex-info "Workspace is already leased"
                        {:workspace workspace :lease-id (:lease-id ws)})))))
  (let [lease-id    (str (UUID/randomUUID))
        now         (Instant/now)
        expires-at  (.plus now (Duration/ofSeconds ttl-seconds))
        lease-type  (keyword lease-type)
        ;; Force build lease-type when workspace is specified
        lease-type  (if workspace :build lease-type)
        result      (atom nil)]
    ;; Atomic swap: find resource and update state
    (swap! state/state
           (fn [s]
             (let [;; If workspace exists, prefer its resource
                   ws-resource-id (when workspace
                                    (:resource-id (get-in s [:workspaces workspace])))
                   candidates (if (= lease-type :build)
                                (if ws-resource-id
                                  ;; Workspace already assigned to a resource — use it
                                  (let [r (get-in s [:resources ws-resource-id])]
                                    (when (and r (#{:warm :shared} (:status r))
                                               (< (count (:active-leases r #{}))
                                                  (:max-slots r 10)))
                                      [r]))
                                  ;; Build: warm or shared with capacity
                                  (->> (vals (:resources s))
                                       (filter #(and (= (:type %) :vm)
                                                     (= (:platform %) (keyword platform))
                                                     (or (= (:status %) :warm)
                                                         (and (= (:status %) :shared)
                                                              (< (count (:active-leases % #{}))
                                                                 (:max-slots % 10))))))
                                       (sort-by :id)))
                                ;; Phone: warm only (exclusive)
                                (->> (vals (:resources s))
                                     (filter #(and (= (:status %) :warm)
                                                   (= (:type %) (keyword type))
                                                   (= (:platform %) (keyword platform))))
                                     (sort-by :id)))]
               (if-let [resource (first candidates)]
                 (let [lease (cond-> {:id          lease-id
                                      :resource-id (:id resource)
                                      :host        (:host resource)
                                      :lessee      lessee
                                      :lease-type  lease-type
                                      :ttl-seconds ttl-seconds
                                      :acquired-at now
                                      :expires-at  expires-at}
                               workspace (assoc :workspace workspace))]
                   (reset! result {:lease lease :resource resource})
                   (if (= lease-type :build)
                     ;; Build lease: add to active-leases, mark shared
                     (cond-> s
                       true (update-in [:resources (:id resource) :active-leases]
                                       (fnil conj #{}) lease-id)
                       true (assoc-in [:resources (:id resource) :status] :shared)
                       true (assoc-in [:resources (:id resource) :updated-at] now)
                       true (assoc-in [:leases lease-id] lease)
                       ;; Update workspace status
                       workspace (assoc-in [:workspaces workspace :status] :leased)
                       workspace (assoc-in [:workspaces workspace :lease-id] lease-id))
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
        ;; Build lease: create/ensure macOS user, then start tunnel
        (let [user-info (if workspace
                          (macos/ensure-user! resource workspace)
                          (macos/create-user! resource lease-id))]
          (if user-info
            (let [tunnel (start-tunnel! lease-id resource)
                  {:keys [ssh-host ssh-port]} (:connection resource)
                  connection {:tunnel-port (:port tunnel)
                              :ssh-user    (:macos-user user-info)
                              :ssh-host    ssh-host
                              :ssh-port    (or ssh-port 10022)
                              :home-dir    (:home-dir user-info)}]
              ;; Update lease and workspace state
              (swap! state/state
                     (fn [s]
                       (cond-> s
                         true (assoc-in [:leases lease-id :connection] connection)
                         true (assoc-in [:leases lease-id :macos-user] (:macos-user user-info))
                         ;; Register/update workspace
                         workspace (assoc-in [:workspaces workspace]
                                             {:name        workspace
                                              :resource-id (:id resource)
                                              :macos-user  (:macos-user user-info)
                                              :status      :leased
                                              :lease-id    lease-id
                                              :created-at  (or (:created-at (get-in s [:workspaces workspace]))
                                                               now)}))))
              (log/info (if workspace "Workspace" "Build") "lease acquired:" lease-id
                        "resource:" (:id resource) "lessee:" lessee
                        "user:" (:macos-user user-info)
                        (when workspace (str "workspace:" workspace)))
              (state/record-event! "lease"
                {:lessee    lessee
                 :container (:container resource)
                 :resource  (:id resource)
                 :lease-id  lease-id
                 :lease-type "build"
                 :ttl       ttl-seconds
                 :workspace workspace
                 :user      (:macos-user user-info)})
              (assoc lease
                     :connection connection
                     :macos-user (:macos-user user-info)))
            ;; User creation failed — rollback
            (do
              (log/error "Failed to create macOS user for lease" lease-id "- rolling back")
              (unlease! lease-id)
              nil)))
        ;; Phone lease: start tunnel + cascading parent hold
        (let [tunnel (start-tunnel! lease-id resource)
              ;; Cascading lease: if resource has a parent, hold it
              parent-lease-id (when-let [parent-container (:parent resource)]
                                (let [parent-res (first (filter #(= (:container %) parent-container)
                                                                (state/resources)))]
                                  (when parent-res
                                    (log/info "Cascading: holding parent" (:id parent-res)
                                              "for iOS lease" lease-id)
                                    (let [parent-lease (acquire!
                                                         {:type      (name (:type parent-res))
                                                          :platform  (name (:platform parent-res))
                                                          :ttl-seconds ttl-seconds
                                                          :lessee    (str "hold:" lease-id)
                                                          :lease-type :build})]
                                      (when parent-lease
                                        (:id parent-lease))))))]
          ;; Set up reverse port forwarding for server_ports
          (let [port-results (when (seq server-ports)
                               (future (setup-server-ports! lease-id resource (:port tunnel) server-ports)))
                connection (cond-> {:tunnel-port (:port tunnel)}
                             (seq server-ports) (assoc :server-ports server-ports))]
            (swap! state/state
                   (fn [s]
                     (cond-> s
                       true (assoc-in [:leases lease-id :connection] connection)
                       true (assoc-in [:leases lease-id :server-ports] server-ports)
                       parent-lease-id (assoc-in [:leases lease-id :parent-lease-id]
                                                  parent-lease-id))))
            ;; Wait for port forwarding to complete and merge results
            (let [port-map (when port-results @port-results)
                  final-connection (cond-> connection
                                     port-map (assoc :server-port-status port-map))]
              (when port-map
                (swap! state/state assoc-in [:leases lease-id :connection] final-connection))
              (log/info "Phone lease acquired:" lease-id "resource:" (:id resource)
                        "lessee:" lessee "ttl:" ttl-seconds "s"
                        "tunnel-port:" (:port tunnel)
                        (when (seq server-ports) (str "server-ports:" server-ports))
                        (when parent-lease-id (str "parent-hold:" parent-lease-id)))
              (state/record-event! "lease"
                {:lessee    lessee
                 :container (:container resource)
                 :resource  (:id resource)
                 :lease-id  lease-id
                 :lease-type "phone"
                 :ttl       ttl-seconds})
              (cond-> (assoc lease :connection final-connection)
                parent-lease-id (assoc :parent-lease-id parent-lease-id)))))))))

;; ---------------------------------------------------------------------------
;; Unlease
;; ---------------------------------------------------------------------------

(defn unlease!
  "Unlease a resource: update resource state, remove lease, stop tunnel.
   For build leases: removes from active-leases, deletes macOS user.
   For phone leases: marks resource warm (legacy behavior).
   Returns true if lease was found and unleased."
  [lease-id]
  (let [unleased-lease (atom nil)]
    (swap! state/state
           (fn [s]
             (if-let [lease (get-in s [:leases lease-id])]
               (let [resource-id (:resource-id lease)
                     build? (= (:lease-type lease) :build)]
                 (reset! unleased-lease lease)
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
    (when-let [lease @unleased-lease]
      ;; Clean up outside the atom swap
      (when (= (:lease-type lease) :build)
        (if (:workspace lease)
          ;; Workspace lease: mark workspace idle, keep user
          (do
            (swap! state/state
                   (fn [s]
                     (-> s
                         (assoc-in [:workspaces (:workspace lease) :status] :idle)
                         (assoc-in [:workspaces (:workspace lease) :lease-id] nil))))
            (log/info "Workspace" (:workspace lease) "returned to idle"))
          ;; Ephemeral build: delete user
          (when-let [macos-user (:macos-user lease)]
            (let [resource (state/resource (:resource-id lease))]
              (when resource
                (future
                  (try
                    (macos/delete-user! resource macos-user)
                    (catch Exception e
                      (log/warn "Failed to delete macOS user" macos-user ":" (.getMessage e))))))))))
      ;; Clean up reverse port forwarding if present
      (when-let [server-ports (seq (:server-ports lease))]
        (let [resource (state/resource (:resource-id lease))
              tunnel (get @tunnels lease-id)]
          (when (and resource tunnel)
            (teardown-server-ports! resource (:port tunnel) server-ports))))
      (stop-tunnel! lease-id)
      ;; Cascading: release parent hold lease if present
      (when-let [parent-lid (:parent-lease-id lease)]
        (log/info "Cascading: releasing parent hold" parent-lid "for lease" lease-id)
        (unlease! parent-lid))
      (log/info "Lease unleased:" lease-id
                (when (= (:lease-type lease) :build)
                  (str (if (:workspace lease)
                         (str "(workspace: " (:workspace lease) ")")
                         (str "(build user: " (:macos-user lease) ")")))))
      (state/record-event! "unlease"
        {:lessee       (:lessee lease)
         :container    (some-> (state/resource (:resource-id lease)) :container)
         :resource     (:resource-id lease)
         :lease-id     lease-id
         :lease-type   (some-> (:lease-type lease) name)
         :workspace    (:workspace lease)
         :held-seconds (when-let [acq (:acquired-at lease)]
                         (.getSeconds (Duration/between acq (Instant/now))))}))
    (boolean @unleased-lease)))

;; ---------------------------------------------------------------------------
;; Workspace management
;; ---------------------------------------------------------------------------

(defn purge-workspace!
  "Delete a workspace and its macOS user account.
   Only works when workspace is idle (not leased).
   Returns true if purged, false if not found or busy."
  [workspace-name]
  (let [ws (state/workspace workspace-name)]
    (cond
      (nil? ws)
      (do (log/warn "Workspace not found:" workspace-name) false)

      (= (:status ws) :leased)
      (do (log/warn "Cannot purge leased workspace:" workspace-name) false)

      :else
      (let [resource (state/resource (:resource-id ws))]
        ;; Delete macOS user
        (when resource
          (macos/delete-user! resource (:macos-user ws)))
        ;; Remove from state
        (swap! state/state update :workspaces dissoc workspace-name)
        (log/info "Purged workspace:" workspace-name)
        true))))

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
       (state/record-event! "gc"
         {:lessee    (:lessee lease)
          :container (some-> (state/resource (:resource-id lease)) :container)
          :resource  (:resource-id lease)
          :lease-id  (:id lease)})
       (unlease! (:id lease)))
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
