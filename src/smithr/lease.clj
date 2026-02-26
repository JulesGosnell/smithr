(ns smithr.lease
  "Lease acquire/unlease/GC logic.
   Uses swap! on state atom for atomic compare-and-set semantics.
   Manages SSH tunnels: created on acquire, destroyed on unlease/GC."
  (:require [clojure.string]
            [clojure.tools.logging :as log]
            [clojure.java.shell :as shell]
            [smithr.state :as state]
            [smithr.docker :as docker]
            [smithr.macos :as macos]
            [smithr.linux :as linux]
            [smithr.provision :as provision])
  (:import [java.time Instant Duration]
           [java.util UUID]
           [java.net DatagramSocket InetAddress]))

(defn lan-ip
  "Return this host's LAN IP address (not loopback).
   Uses the UDP socket trick: connect a datagram socket to an external
   address (never sends traffic) and read the local address chosen by
   the kernel's routing table."
  []
  (with-open [sock (DatagramSocket.)]
    (.connect sock (InetAddress/getByName "8.8.8.8") 53)
    (.getHostAddress (.getLocalAddress sock))))

;; ---------------------------------------------------------------------------
;; SSH tunnel management
;; ---------------------------------------------------------------------------

(declare stop-tunnel!)

(defonce ^:private tunnels
  (atom {}))  ;; lease-id -> {:process Process, :port int}

(defonce ^:private next-tunnel-port
  (atom 17000))

(defn cleanup-stale-tunnels!
  "Kill orphaned SSH tunnel processes from previous Smithr sessions.
   Called on startup before accepting new leases.
   Uses both pattern-based pkill and port-based detection for robustness."
  []
  ;; First pass: pkill by command pattern
  (try
    (let [{:keys [exit]} (shell/sh "pkill" "-f" "ssh -N.*-L.*170")]
      (when (zero? exit)
        (log/info "Killed stale SSH tunnels by command pattern")
        (Thread/sleep 500))) ;; wait for processes to actually exit
    (catch Exception _ nil))
  ;; Second pass: find any process bound to 170xx ports via ss
  (try
    (let [{:keys [out]} (shell/sh "ss" "-tlnp" "sport" ">=" "17000" "and" "sport" "<=" "17099")]
      (when-let [pids (seq (re-seq #"pid=(\d+)" out))]
        (doseq [[_ pid] pids]
          (log/warn "Killing orphaned process on tunnel port range: pid" pid)
          (shell/sh "kill" pid))
        (Thread/sleep 500)))
    (catch Exception e
      (log/debug "Port-based cleanup skipped:" (.getMessage e))))
  (log/info "Stale tunnel cleanup complete"))

(defn- port-in-use?
  "Check if a TCP port is already bound on this host."
  [port]
  (try
    (let [sock (java.net.ServerSocket.)]
      (try
        (.setReuseAddress sock false)
        (.bind sock (java.net.InetSocketAddress. (int port)))
        false ;; bind succeeded → port is free
        (finally (.close sock))))
    (catch java.net.BindException _ true)
    (catch Exception _ false)))

(defn- allocate-tunnel-port!
  "Allocate the next available tunnel port, skipping ports already in use.
   Orphaned SSH tunnels from previous Smithr sessions can hold ports —
   this ensures we never collide with them."
  []
  (loop [attempts 0]
    (when (> attempts 100)
      (throw (ex-info "Cannot allocate tunnel port — too many ports in use" {})))
    (let [port (swap! next-tunnel-port inc)
          port (dec port)]
      (if (port-in-use? port)
        (do (log/warn "Tunnel port" port "already in use, skipping")
            (recur (inc attempts)))
        port))))

(defn- docker-network-ip?
  "Is this a Docker network IP reachable only from the local host?
   Matches 10.x.x.x (smithr-network) and 172.16-31.x.x (Docker default bridges)."
  [host]
  (and host (or (re-matches #"10\.\d+\.\d+\.\d+" host)
                (re-matches #"172\.(1[6-9]|2\d|3[01])\.\d+\.\d+" host))))

(defn- resolve-tunnel-route
  "Given a target host:port, determine SSH -L forward and hop host.
   Docker network IPs → hop via localhost (same host can reach Docker network).
   Remote hostnames → hop via that host, forward to localhost on the remote."
  [target-host target-port]
  (if (docker-network-ip? target-host)
    {:fwd-host target-host :fwd-port target-port :hop "localhost"}
    {:fwd-host "localhost" :fwd-port target-port :hop (or target-host "localhost")}))

(defn- await-port-ready
  "Poll localhost:port until it accepts a TCP connection. Returns true if ready,
   false if timeout exceeded. Checks every 200ms up to timeout-ms."
  [port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        false
        (if (try
              (let [sock (java.net.Socket.)]
                (try
                  (.connect sock (java.net.InetSocketAddress. "localhost" (int port)) 200)
                  true
                  (finally (.close sock))))
              (catch Exception _ false))
          true
          (do (Thread/sleep 200) (recur)))))))

(defn- await-adb-ready!
  "Verify ADB is responsive through a tunnel port. Retries several times.
   Disconnects first to clear stale ADB server state from previous leases.
   Returns true if ADB responds, false on timeout."
  [tunnel-port timeout-ms]
  (let [adb-target (str "localhost:" tunnel-port)
        deadline   (+ (System/currentTimeMillis) timeout-ms)]
    ;; Clear any stale ADB connection cached from a previous lease on this port
    (shell/sh "adb" "disconnect" adb-target)
    (loop [attempt 1]
      (if (> (System/currentTimeMillis) deadline)
        (do (log/warn "ADB readiness check timed out on port" tunnel-port
                      "after" attempt "attempts")
            false)
        (let [{:keys [exit out]} (shell/sh "adb" "connect" adb-target)
              out-str (clojure.string/trim (str out))]
          (if (and (zero? exit) (re-find #"connected" out-str))
            ;; Connected — verify device actually responds
            (let [{:keys [exit out]} (shell/sh "adb" "-s" adb-target "shell"
                                               "getprop" "sys.boot_completed")
                  prop-str (clojure.string/trim (str out))]
              (if (and (zero? exit) (= prop-str "1"))
                (do (log/info "ADB ready on port" tunnel-port "after" attempt "attempts")
                    true)
                (do (log/debug "ADB connect ok but device not ready on port" tunnel-port
                               "attempt" attempt "- getprop:" prop-str)
                    (Thread/sleep 1000) (recur (inc attempt)))))
            (do (log/debug "ADB connect failed on port" tunnel-port
                           "attempt" attempt ":" out-str)
                (Thread/sleep 1000) (recur (inc attempt)))))))))

(defn- start-socat-bridge!
  "Start a socat bridge for resources that don't need SSH tunnels.
   Forwards from an allocated tunnel port directly to the target IP:port.
   Works for server containers (container-ip), physical phones (adb-host), etc.
   Returns tunnel info map compatible with start-tunnel!."
  [lease-id resource target-port]
  (let [conn (:connection resource)
        target-ip (or (:container-ip conn) (:adb-host conn) (:wifi-ip conn) "localhost")
        tunnel-port (allocate-tunnel-port!)
        target-str (str target-ip ":" target-port)]
    (log/info "Starting socat bridge on port" tunnel-port "for lease" lease-id
              "→" target-str)
    (try
      (let [cmd ["socat"
                 (str "TCP-LISTEN:" tunnel-port ",fork,reuseaddr")
                 (str "TCP:" target-str)]
            proc (-> (ProcessBuilder. ^java.util.List cmd)
                     (.redirectErrorStream true)
                     (.start))
            tunnel-info {:port        tunnel-port
                         :process     proc
                         :resource-id (:id resource)
                         :target      target-str
                         :started-at  (Instant/now)}]
        (swap! tunnels assoc lease-id tunnel-info)
        (if (await-port-ready tunnel-port 5000)
          (do (log/info "Socat bridge ready: port" tunnel-port "→" target-str "pid" (.pid proc))
              tunnel-info)
          (do (if (.isAlive proc)
                (log/error "Socat process alive but port" tunnel-port
                           "not accepting connections after 5s")
                (log/error "Socat exited on port" tunnel-port))
              (stop-tunnel! lease-id)
              nil)))
      (catch Exception e
        (log/error "Failed to start socat bridge on port" tunnel-port ":" (.getMessage e))
        nil))))

(defn- start-tunnel!
  "Start an SSH tunnel for a lease. Returns tunnel info map.
   Uses `ssh -N -L` for port forwarding. One SSH process per lease.
   Waits for the tunnel port to accept connections before returning.
   Kill the process → client is disconnected.

   For build leases, optional reverse-ports adds -R flags to the same SSH
   session so the remote container can reach Smithr tunnel ports directly.
   Format: [{:bind port-on-remote, :target tunnel-port-on-localhost}]"
  [lease-id resource & {:keys [reverse-ports]}]
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
          :android-build [(or ssh-host "localhost") (or ssh-port 22)]
          ;; Default: container-ip + service-port (servers, adopted containers, etc.)
          (let [conn (:connection resource)
                ip   (or (:container-ip conn) "localhost")
                port (or (:service-port conn) 3000)]
            [ip port]))
        ;; Resolve forwarding route: Docker IPs hop via localhost, remote hops via hostname.
        ;; Special case: server-type resources with remote ssh-host — hop via ssh-host to
        ;; reach container IP on the remote Docker network.
        {:keys [fwd-host fwd-port hop]}
        (if (and (docker-network-ip? target-host)
                 (not= (get-in resource [:connection :ssh-host] "localhost") "localhost"))
          ;; Cross-host: hop through remote host to reach container IP
          {:fwd-host target-host :fwd-port target-port
           :hop (get-in resource [:connection :ssh-host])}
          (resolve-tunnel-route target-host target-port))
        ;; When reverse-ports are present, SSH must connect directly to the build
        ;; container so -R binds on the container's sshd (not on the hop host).
        ;; For Docker IPs: SSH directly to the container, -L forwards to localhost.
        ;; For remote hosts: SSH to the container via ProxyJump through the remote host.
        ;; Build containers use user "smithr" and the tunnel SSH key for auth.
        ;;
        ;; IMPORTANT: For remote hosts, the resource's ssh-port is the HOST-mapped port
        ;; (e.g. 2222), used in -p to reach the container. But -L must forward to the
        ;; container's INTERNAL port (e.g. 22), since we're inside the container's sshd.
        [final-hop final-fwd-host final-fwd-port extra-ssh-args]
        (if (seq reverse-ports)
          (let [key-path (linux/tunnel-ssh-key)
                ssh-user "smithr"
                key-args (if key-path ["-i" key-path] [])
                port-args (when ssh-port ["-p" (str ssh-port)])
                ;; When SSHing into a VM container (macOS/iOS), the SSH lands
                ;; inside the VM (QEMU forwards container:10022 → VM:22).
                ;; The -L forward must target the VM's sshd (port 22), not
                ;; the container's QEMU port mapping (10022).
                internal-port (case platform
                                :android-build 22
                                :macos 22
                                :ios 22
                                fwd-port)]
            (if (docker-network-ip? target-host)
              ;; Local Docker container: SSH directly to it
              [(str ssh-user "@" target-host) "localhost" internal-port
               (vec (concat key-args port-args))]
              ;; Remote host: SSH via ProxyJump
              [(str ssh-user "@" target-host) "localhost" internal-port
               (vec (concat key-args port-args ["-J" (or hop "localhost")]))]))
          ;; No reverse ports: use standard routing
          [hop fwd-host fwd-port []])
        target-str (str final-fwd-host ":" final-fwd-port " via " final-hop)
        ;; Build -R flags for reverse ports (build leases only)
        ;; Each entry has :bind (port on remote), :host (target host), :target (target port)
        ;; e.g. -R 5593:10.21.0.1:17003 routes container:5593 → 10.21.0.1:17003
        reverse-flags (when (seq reverse-ports)
                        (mapcat (fn [{:keys [bind host target]}]
                                  (let [rhost (or host "localhost")]
                                    (log/info "Reverse tunnel: remote:" bind "→" (str rhost ":" target)
                                              "for lease" lease-id)
                                    ["-R" (str bind ":" rhost ":" target)]))
                                reverse-ports))
        ;; ExitOnForwardFailure: only when no reverse-ports. With reverse-ports,
        ;; shared server ports (3000, 4443) may already be bound by a sibling
        ;; lease's SSH session on the same container — that's fine, we just need
        ;; the -L forward to work. The duplicate -R silently fails.
        exit-on-fail (if (seq reverse-ports) "no" "yes")
        cmd (vec (concat ["ssh" "-N"
                         "-o" "StrictHostKeyChecking=no"
                         "-o" "UserKnownHostsFile=/dev/null"
                         "-o" "BatchMode=yes"
                         "-o" (str "ExitOnForwardFailure=" exit-on-fail)
                         "-o" "ServerAliveInterval=30"
                         "-o" "ServerAliveCountMax=3"]
                        extra-ssh-args
                        ["-L" (str "0.0.0.0:" tunnel-port ":" final-fwd-host ":" final-fwd-port)]
                        reverse-flags
                        [final-hop]))]
    (log/info "Starting SSH tunnel on port" tunnel-port "for lease" lease-id
              "→" target-str
              (when (seq reverse-ports)
                (str "with " (count reverse-ports) " reverse port(s)")))
    (try
      (let [proc (-> (ProcessBuilder. ^java.util.List cmd)
                     (.redirectErrorStream true)
                     (.start))
            tunnel-info (cond-> {:port        tunnel-port
                                :process     proc
                                :resource-id (:id resource)
                                :target      target-str
                                :started-at  (Instant/now)}
                         (seq reverse-ports) (assoc :reverse-ports reverse-ports))]
        (swap! tunnels assoc lease-id tunnel-info)
        ;; Wait for tunnel port to actually accept connections (up to 10s)
        (if (await-port-ready tunnel-port 10000)
          (do (log/info "SSH tunnel ready: port" tunnel-port "→" target-str "pid" (.pid proc))
              tunnel-info)
          (do (if (.isAlive proc)
                (log/error "SSH tunnel process alive but port" tunnel-port
                           "not accepting connections after 10s")
                (log/error "SSH tunnel exited on port" tunnel-port
                           "— check SSH access to" final-hop))
              ;; Clean up failed tunnel
              (stop-tunnel! lease-id)
              nil)))
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
    ;; Kill the tunnel process (socat, iproxy, ssh) if present
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

(defn- platform-user-ops
  "Return the user management functions for a resource's platform.
   macOS uses dscl-based scripts, Linux uses useradd/userdel."
  [resource]
  (if (= (:platform resource) :android-build)
    {:ensure-user!  linux/ensure-user!
     :create-user!  linux/create-user!
     :delete-user!  linux/delete-user!}
    {:ensure-user!  macos/ensure-user!
     :create-user!  macos/create-user!
     :delete-user!  macos/delete-user!}))

;; ---------------------------------------------------------------------------
;; Shared filesystem lease locks
;; ---------------------------------------------------------------------------
;; Phone leases use atomic mkdir on /srv/shared/smithr/leases/<resource-id>/
;; as a cross-host lock. Both Smithr instances see the same NFS mount, so mkdir is
;; the coordination primitive — no proxying or shared database needed.

(def ^:private shared-lease-dir "/srv/shared/smithr/leases")

(defn- resource-id->lock-path
  "Convert resource ID to a filesystem-safe lock directory path.
   e.g. 'megalodon:android:smithr-android-fe' -> '/srv/shared/smithr/leases/megalodon--android--smithr-android-fe'"
  [resource-id]
  (str shared-lease-dir "/"
       (clojure.string/replace (str resource-id) ":" "--")))

(defn- try-acquire-shared-lock!
  "Atomically acquire a shared filesystem lock for a phone resource.
   Returns true if lock was acquired, false if already held.
   Writes lessee info into the lock directory for debugging."
  [resource-id lessee lease-id]
  (let [lock-path (resource-id->lock-path resource-id)
        lock-dir  (java.io.File. lock-path)]
    (if (.mkdir lock-dir)
      (do
        ;; Lock acquired — write lessee info for debugging
        (try
          (spit (str lock-path "/lessee") (str lessee "\n" lease-id "\n" (Instant/now)))
          (catch Exception e
            (log/warn "Failed to write lessee info:" (.getMessage e))))
        (log/info "Shared lock acquired:" resource-id "by" lessee)
        true)
      (do
        (log/debug "Shared lock busy:" resource-id)
        false))))

(defn- release-shared-lock!
  "Release a shared filesystem lock for a phone resource."
  [resource-id]
  (let [lock-path (resource-id->lock-path resource-id)
        lock-dir  (java.io.File. lock-path)]
    (when (.exists lock-dir)
      ;; Remove contents first, then directory
      (doseq [f (.listFiles lock-dir)]
        (.delete f))
      (.delete lock-dir)
      (log/info "Shared lock released:" resource-id))))

(defn- shared-lock-held?
  "Check if a shared filesystem lock is held for a phone resource."
  [resource-id]
  (.isDirectory (java.io.File. (resource-id->lock-path resource-id))))

(defn cleanup-stale-shared-locks!
  "Remove any shared locks that don't correspond to active leases.
   Called on startup to clean up after crashes."
  []
  (let [lease-dir (java.io.File. shared-lease-dir)]
    (when (.exists lease-dir)
      (doseq [lock-dir (.listFiles lease-dir)]
        (when (.isDirectory lock-dir)
          (let [resource-id (clojure.string/replace (.getName lock-dir) "--" ":")]
            (when-not (some (fn [[_ l]] (= (:resource-id l) resource-id))
                           (:leases @state/state))
              (log/info "Cleaning stale shared lock:" resource-id)
              (release-shared-lock! resource-id))))))))

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

   :substrate — optional filter: 'emulated', 'simulated', 'physical', 'virtual'
   :model — optional filter: specific device model (e.g. 'Pixel 8')

   Build leases create a per-user macOS account and SSH tunnel.
   Phone leases get exclusive VM access (same as legacy behavior)."
  [{:keys [type platform ttl-seconds lessee lease-type workspace server-ports
           prefer-host reverse-ports substrate model retried?]
    :or   {ttl-seconds 1800
           lessee      "anonymous"
           lease-type  :phone}}]
  ;; Validate workspace name if provided
  (when (and workspace (not (valid-workspace-name? workspace)))
    (log/warn "Invalid workspace name:" workspace)
    (throw (ex-info "Invalid workspace name" {:workspace workspace})))
  ;; Check if workspace is already leased — auto-evict stale leases
  ;; When the same workspace is re-requested, the old proxy is almost certainly
  ;; dead (SIGKILL'd, OOM'd, etc.) and couldn't clean up its lease.
  ;; Safe to evict because workspace names are unique per build type and CI
  ;; uses cancel-in-progress:false, so overlapping requests = dead proxy.
  (when workspace
    (when-let [ws (state/workspace workspace)]
      (when (= (:status ws) :leased)
        (let [old-lease-id (:lease-id ws)]
          (log/warn "Workspace" workspace "already leased by" old-lease-id
                    "- auto-evicting (likely dead proxy)")
          (unlease! old-lease-id)))))
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
                   ;; Optional substrate/model filtering predicate
                   substrate-pred (if substrate
                                    #(= (:substrate %) substrate)
                                    (constantly true))
                   model-pred (if model
                                #(= (:model %) model)
                                (constantly true))
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
                                       (filter #(and (= (:type %) (keyword type))
                                                     (= (:platform %) (keyword platform))
                                                     (substrate-pred %)
                                                     (model-pred %)
                                                     (or (= (:status %) :warm)
                                                         (and (= (:status %) :shared)
                                                              (< (count (:active-leases % #{}))
                                                                 (:max-slots % 10))))))
                                       (sort-by (juxt #(if (= (:host %) prefer-host) 0 1) :id))))
                                ;; Phone: warm + no shared lock held (cross-host safe)
                                (->> (vals (:resources s))
                                     (filter #(and (= (:status %) :warm)
                                                   (= (:type %) (keyword type))
                                                   (= (:platform %) (keyword platform))
                                                   (substrate-pred %)
                                                   (model-pred %)
                                                   (not (shared-lock-held? (:id %)))))
                                     (sort-by (juxt #(if (= (:host %) prefer-host) 0 1) :id))))]
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
    ;; If no candidates found and provisioning is available, try lazy provisioning
    (if (and (nil? @result) (not retried?)
             (provision/can-provision? (keyword type) (keyword platform)))
      (do
        (log/info "No warm resource for" type platform "- attempting lazy provisioning")
        (if-let [_provisioned-id (provision/ensure-resource! {:type type :platform platform})]
          ;; Resource is now warm — retry the acquire once
          (do
            (log/info "Provisioning succeeded, retrying acquire")
            (acquire! {:type type :platform platform
                       :ttl-seconds ttl-seconds :lessee lessee
                       :lease-type lease-type :workspace workspace
                       :server-ports server-ports :prefer-host prefer-host
                       :reverse-ports reverse-ports
                       :substrate substrate :model model
                       :retried? true}))
          ;; Provisioning failed
          nil))
    (when-let [{:keys [lease resource]} @result]
      (if (= lease-type :build)
        (if (= (:type resource) :server)
          ;; Server build lease: just tunnel, no user creation
          (if-let [tunnel (let [svc-port (get-in resource [:connection :service-port] 3000)
                                ssh-host (get-in resource [:connection :ssh-host] "localhost")
                                local?   (= ssh-host "localhost")]
                            (if local?
                              (start-socat-bridge! lease-id resource svc-port)
                              (start-tunnel! lease-id resource)))]
            (let [connection {:tunnel-port (:port tunnel)}]
              (swap! state/state assoc-in [:leases lease-id :connection] connection)
              (log/info "Server lease acquired:" lease-id
                        "resource:" (:id resource) "lessee:" lessee)
              (state/record-event! "lease"
                {:lessee    lessee
                 :container (:container resource)
                 :resource  (:id resource)
                 :lease-id  lease-id
                 :lease-type "build"
                 :ttl       ttl-seconds})
              (assoc lease :connection connection))
            ;; Tunnel failed — rollback
            (do
              (log/error "Failed to start tunnel for server lease" lease-id "- rolling back")
              (unlease! lease-id)
              nil))
          ;; VM build lease: create/ensure user, then start tunnel
          (let [{:keys [ensure-user! create-user!]} (platform-user-ops resource)
                cached-ws (when workspace (state/workspace workspace))
                user-info (cond
                            ;; Workspace user already verified on this resource — skip SSH
                            (and workspace cached-ws
                                 (:macos-user cached-ws)
                                 (:home-dir cached-ws)
                                 (= (:resource-id cached-ws) (:id resource)))
                            (do
                              (log/info "Workspace" workspace "user cached on"
                                        (:id resource) "- skipping ensure-user")
                              {:macos-user (:macos-user cached-ws)
                               :home-dir   (:home-dir cached-ws)})
                            ;; Named workspace but not cached — create/verify via SSH
                            workspace
                            (ensure-user! resource workspace)
                            ;; Ephemeral build — always create new user
                            :else
                            (create-user! resource lease-id))]
            (if user-info
              (if-let [tunnel (start-tunnel! lease-id resource :reverse-ports reverse-ports)]
                (let [{:keys [ssh-host ssh-port]} (:connection resource)
                      connection (cond-> {:tunnel-port (:port tunnel)
                                          :ssh-user    (:macos-user user-info)
                                          :ssh-host    ssh-host
                                          :ssh-port    (or ssh-port 10022)
                                          :home-dir    (:home-dir user-info)}
                                   (seq reverse-ports) (assoc :reverse-ports
                                                              (into {} (map (fn [{:keys [bind target]}]
                                                                             [(str bind) target])
                                                                           reverse-ports))))]
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
                                                :home-dir    (:home-dir user-info)
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
                ;; Tunnel failed — rollback
                (do
                  (log/error "Failed to start tunnel for lease" lease-id "- rolling back")
                  (unlease! lease-id)
                  nil))
              ;; User creation failed — rollback
              (do
                (log/error "Failed to create macOS user for lease" lease-id "- rolling back")
                (unlease! lease-id)
                nil))))
        ;; Phone lease: acquire shared lock, start tunnel, ADB check, cascading parent
        (if-not (try-acquire-shared-lock! (:id resource) lessee lease-id)
          ;; Shared lock failed — another Smithr instance grabbed it between check and swap
          (do
            (log/warn "Shared lock race lost for" (:id resource) "- rolling back")
            (unlease! lease-id)
            nil)
        (if-let [tunnel (cond
                          ;; Server resources: socat if local, SSH tunnel if remote
                          ;; Local = ssh-host is "localhost" (same host as Smithr)
                          ;; Remote = ssh-host is a hostname → tunnel via that host
                          (= (:type resource) :server)
                          (let [svc-port (get-in resource [:connection :service-port] 3000)
                                ssh-host (get-in resource [:connection :ssh-host] "localhost")
                                local?   (= ssh-host "localhost")]
                            (if local?
                              (start-socat-bridge! lease-id resource svc-port)
                              (start-tunnel! lease-id resource)))
                          ;; All others (including physical devices): SSH tunnel
                          ;; Physical devices use wrapper containers with connect-host/port
                          ;; labels pointing to the host bridge (adb forward + socat or
                          ;; iproxy), so they tunnel identically to emulated devices.
                          :else
                          (start-tunnel! lease-id resource))]
          (if (and (= (:platform resource) :android)
                   (not (await-adb-ready! (:port tunnel) 15000)))
            ;; ADB not ready through tunnel — rollback
            (do
              (log/error "ADB not responsive through tunnel port" (:port tunnel)
                         "for lease" lease-id "- rolling back")
              (unlease! lease-id)
              nil)
          (let [;; Cascading lease: if resource has a parent, hold it
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
                             (seq server-ports) (assoc :server-ports server-ports)
                             ;; Include UDID for physical iOS devices
                             (get-in resource [:connection :udid])
                             (assoc :udid (get-in resource [:connection :udid]))
                             ;; Include device name for physical devices
                             (:device-name resource)
                             (assoc :device-name (:device-name resource)))]
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
                        (when (:device-name resource) (str "\"" (:device-name resource) "\""))
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
                parent-lease-id (assoc :parent-lease-id parent-lease-id))))))
          ;; Tunnel failed — rollback phone lease
          (do
            (log/error "Failed to start tunnel for phone lease" lease-id "- rolling back")
            (unlease! lease-id)
            nil))))))))  ;; extra paren closes the if-provisioning branch

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
                (let [{delete-user! :delete-user!} (platform-user-ops resource)]
                  (future
                    (try
                      (delete-user! resource macos-user)
                      (catch Exception e
                        (log/warn "Failed to delete user" macos-user ":" (.getMessage e)))))))))))
      ;; Clean up reverse port forwarding if present
      (when-let [server-ports (seq (:server-ports lease))]
        (let [resource (state/resource (:resource-id lease))
              tunnel (get @tunnels lease-id)]
          (when (and resource tunnel)
            (teardown-server-ports! resource (:port tunnel) server-ports))))
      (stop-tunnel! lease-id)
      ;; Release shared filesystem lock for phone leases
      (when (not= (:lease-type lease) :build)
        (release-shared-lock! (:resource-id lease)))
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
        ;; Delete user
        (when resource
          (let [{delete-user! :delete-user!} (platform-user-ops resource)]
            (delete-user! resource (:macos-user ws))))
        ;; Remove from state
        (swap! state/state update :workspaces dissoc workspace-name)
        (log/info "Purged workspace:" workspace-name)
        true))))

;; ---------------------------------------------------------------------------
;; Adopt protocol — register external containers with SSH tunnels
;; ---------------------------------------------------------------------------

(defonce ^:private adopt-tunnels
  (atom {}))  ;; adopt-id -> [{:port int, :process Process, :target-port int} ...]

(defn adopt!
  "Adopt an external container: locate it, create SSH tunnels for specified ports,
   and register it as a leasable resource so clients can lease it like any other.
   Returns the adopt record on success, or throws on failure.
   Tunnels are cleaned up automatically when the container dies (Docker event)."
  [{:keys [container-name ports lessee ttl-seconds resource-type resource-platform max-slots]
    :or   {ttl-seconds       3600
           lessee            "anonymous"
           resource-type     "server"
           resource-platform "linux"
           max-slots         100}}]
  (let [found (docker/find-container container-name)]
    (when-not found
      (throw (ex-info "Container not found" {:container-name container-name :not-found true})))
    (let [{:keys [container host-label host-address]} found
          container-id (.getId container)
          ip (docker/container-ip-any-network container)
          adopt-id (str (UUID/randomUUID))
          now (Instant/now)
          expires-at (.plus now (Duration/ofSeconds ttl-seconds))
          ;; For each port, create a tunnel
          tunnel-infos
          (doall
            (for [port ports]
              (let [tunnel-port (allocate-tunnel-port!)
                    ;; Determine tunnel route based on local vs remote host
                    ;; Local: SSH to localhost, forward to container IP
                    ;; Remote: SSH to remote host, forward to container IP there
                    {:keys [fwd-host fwd-port hop]}
                    (if host-address
                      ;; Remote host: hop via hostname, forward to container IP
                      {:fwd-host (or ip "localhost") :fwd-port port :hop host-address}
                      ;; Local host: hop via localhost, forward to container IP
                      {:fwd-host (or ip "localhost") :fwd-port port :hop "localhost"})
                    cmd ["ssh" "-N"
                         "-o" "StrictHostKeyChecking=no"
                         "-o" "BatchMode=yes"
                         "-o" "ExitOnForwardFailure=yes"
                         "-o" "ServerAliveInterval=30"
                         "-o" "ServerAliveCountMax=3"
                         "-L" (str "0.0.0.0:" tunnel-port ":" fwd-host ":" fwd-port)
                         hop]]
                (log/info "Adopt tunnel:" tunnel-port "→" fwd-host ":" fwd-port "via" hop
                          "for" container-name "port" port)
                (let [proc (-> (ProcessBuilder. ^java.util.List cmd)
                               (.redirectErrorStream true)
                               (.start))]
                  (if (await-port-ready tunnel-port 10000)
                    (do (log/info "Adopt tunnel ready: port" tunnel-port "→" fwd-host ":" fwd-port
                                  "pid" (.pid proc))
                        {:port tunnel-port :process proc :target-port port})
                    (do (log/error "Adopt tunnel failed on port" tunnel-port
                                   "for" container-name "port" port)
                        (.destroyForcibly proc)
                        (throw (ex-info "Tunnel failed"
                                        {:container-name container-name :port port}))))))))
          port-map (into {} (map (fn [{:keys [target-port port]}]
                                   [target-port port])
                                 tunnel-infos))
          adopt {:id             adopt-id
                 :container-name container-name
                 :container-id   container-id
                 :host           host-label
                 :lessee         lessee
                 :ports          port-map
                 :tunnel-host    (lan-ip)
                 :ttl-seconds    ttl-seconds
                 :adopted-at     now
                 :expires-at     expires-at}]
      ;; Store tunnels and state
      (swap! adopt-tunnels assoc adopt-id tunnel-infos)
      (state/add-adopt! adopt)
      ;; Register as a leasable resource so clients can acquire via POST /api/leases
      (let [resource-id (str "adopted:" container-name)]
        (state/upsert-resource!
          {:id             resource-id
           :type           (keyword resource-type)
           :platform       (keyword resource-platform)
           :status         :warm
           :host           host-label
           :container      container-name
           :adopt-id       adopt-id
           :adopted?       true
           :max-slots      max-slots
           :active-leases  #{}
           :connection     {:container-ip ip
                            :service-port (first ports)
                            :ssh-host     (or host-address "localhost")
                            :ssh-port     22}
           :updated-at     now})
        (log/info "Adopted resource registered:" resource-id
                  "type:" resource-type "platform:" resource-platform
                  "max-slots:" max-slots))
      (state/record-event! "adopt"
        {:lessee         lessee
         :container-name container-name
         :adopt-id       adopt-id
         :ports          port-map})
      (log/info "Container adopted:" adopt-id "name:" container-name
                "ports:" port-map "lessee:" lessee)
      adopt)))

(defn unadopt!
  "Clean up an adopted container: kill tunnels, remove resource, remove state.
   Fails if the adopted resource has active leases — unlease first.
   Returns true if found and cleaned up."
  [adopt-id]
  (when-let [adopt (state/adopt adopt-id)]
    (let [resource-id (str "adopted:" (:container-name adopt))]
      ;; Check for active leases on the adopted resource
      (when-let [active-lease (first (filter #(= (:resource-id %) resource-id)
                                              (state/leases)))]
        (throw (ex-info "Cannot unadopt: resource has active lease"
                        {:adopt-id adopt-id
                         :lease-id (:id active-lease)
                         :has-lease true})))
      ;; Kill all tunnel processes
      (when-let [infos (get @adopt-tunnels adopt-id)]
        (doseq [{:keys [port process]} infos]
          (when process
            (try
              (when (.isAlive process)
                (.destroyForcibly process)
                (log/info "Adopt tunnel killed: port" port "pid" (.pid process)))
              (catch Exception e
                (log/warn "Error stopping adopt tunnel on port" port ":" (.getMessage e)))))))
      (swap! adopt-tunnels dissoc adopt-id)
      ;; Remove the synthetic resource
      (state/remove-resource! resource-id)
      (state/remove-adopt! adopt-id)
      (state/record-event! "unadopt"
        {:lessee         (:lessee adopt)
         :container-name (:container-name adopt)
         :adopt-id       adopt-id})
      (log/info "Container unadopted:" adopt-id "name:" (:container-name adopt))
      true)))

;; ---------------------------------------------------------------------------
;; Garbage collection
;; ---------------------------------------------------------------------------

(defn- gc-dead-tunnels!
  "Reap leases whose SSH tunnel process has exited.
   A dead tunnel means the SSH connection to the resource dropped — the lease
   is unusable regardless of TTL. This catches cases where the tunnel fails
   after setup (resource restart, network issue, etc.).
   Only checks leases with tunnels managed by THIS Smithr instance."
  []
  (let [dead-ids (->> @tunnels
                      (filter (fn [[_ tunnel]]
                                (when-let [^Process proc (:process tunnel)]
                                  (not (.isAlive proc)))))
                      (map first))]
    (doseq [lease-id dead-ids]
      (when (state/lease lease-id) ;; still active
        (log/info "GC: tunnel process dead for lease" lease-id "- unleasing")
        (state/record-event! "gc"
          {:lease-id  lease-id
           :resource  (:resource-id (state/lease lease-id))
           :reason    "tunnel-dead"})
        (unlease! lease-id)))
    (count dead-ids)))

(defn gc-expired-leases!
  "Reap expired leases and adopts. Only GCs resources on own-host if specified.
   Also reaps leases with dead tunnel processes."
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
     ;; Also GC expired adopts
     (let [expired-adopts (->> (state/adopts)
                               (filter #(.isAfter now (:expires-at %)))
                               (filter #(or (nil? own-host)
                                            (= own-host (:host %)))))]
       (doseq [adopt expired-adopts]
         (log/info "GC: expiring adopt" (:id adopt)
                   "container:" (:container-name adopt)
                   "lessee:" (:lessee adopt))
         (state/record-event! "gc"
           {:lessee         (:lessee adopt)
            :container-name (:container-name adopt)
            :adopt-id       (:id adopt)})
         ;; Force-unlease any remaining lease on the adopted resource
         (let [resource-id (str "adopted:" (:container-name adopt))
               active-lease (first (filter #(= (:resource-id %) resource-id) (state/leases)))]
           (when active-lease
             (log/info "GC: force-unleasing" (:id active-lease) "for expiring adopt")
             (unlease! (:id active-lease))))
         (unadopt! (:id adopt)))
       ;; Also reap leases with dead tunnel processes
       (let [dead-count (gc-dead-tunnels!)]
         (+ (count expired) (count expired-adopts) dead-count))))))

(defn start-gc-loop!
  "Start the GC background thread. Returns a future for cancellation.
   Optional kwargs:
     :idle-timeout — seconds before idle provisioned resources are deprovisioned
     :device-host — host label for periodic USB device rescanning"
  [interval-seconds own-host & {:keys [idle-timeout device-host]}]
  (log/info "Starting GC loop every" interval-seconds "seconds"
            (if own-host (str "(host: " own-host ")") "(all hosts)")
            (when idle-timeout (str " idle-timeout:" idle-timeout "s"))
            (when device-host (str " device-scan:" device-host)))
  (future
    (while (not (Thread/interrupted))
      (try
        (let [n (gc-expired-leases! own-host)]
          (when (pos? n)
            (log/info "GC reaped" n "expired leases")))
        ;; Idle GC for provisioned resources
        (when idle-timeout
          (try
            (let [n (provision/gc-idle-provisioned! idle-timeout)]
              (when (pos? n)
                (log/info "GC deprovisioned" n "idle resources")))
            (catch Exception e
              (log/error e "Error in provisioning GC"))))
        ;; Periodic device rescan
        (when device-host
          (try
            ((requiring-resolve 'smithr.devices/register-devices!) device-host)
            (catch Exception e
              (log/debug "Device rescan skipped:" (.getMessage e)))))
        (catch InterruptedException _
          (log/info "GC loop interrupted, exiting")
          (.interrupt (Thread/currentThread)))
        (catch Exception e
          (log/error e "Error in GC loop")))
      (Thread/sleep (* interval-seconds 1000)))))
