(ns smithr.docker
  "Docker event subscription and container inspection.
   Uses docker-java to listen for container lifecycle events
   filtered by smithr.managed=true label."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [smithr.state :as state])
  (:import [com.github.dockerjava.core DockerClientImpl DefaultDockerClientConfig]
           [com.github.dockerjava.core.command EventsResultCallback]
           [com.github.dockerjava.httpclient5 ApacheDockerHttpClient$Builder]
           [com.github.dockerjava.api.model Event EventType]
           [com.github.dockerjava.api.exception NotFoundException]
           [java.net URI]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Docker client registry (for container lookup across hosts)
;; ---------------------------------------------------------------------------

(defonce ^:private docker-clients
  (atom {}))  ;; host-label -> {:client :host-address :network-name}

;; ---------------------------------------------------------------------------
;; Docker client creation
;; ---------------------------------------------------------------------------

(defn make-client
  "Create a Docker client for the given URI (unix or tcp).
   For TLS-secured connections, pass :tls-verify true and :cert-path
   pointing to a directory containing ca.pem, cert.pem, key.pem."
  [docker-uri & {:keys [tls-verify cert-path]}]
  (let [config (cond-> (DefaultDockerClientConfig/createDefaultConfigBuilder)
                 true       (.withDockerHost docker-uri)
                 tls-verify (.withDockerTlsVerify (boolean tls-verify))
                 cert-path  (.withDockerCertPath cert-path)
                 true       (.build))
        http-client (-> (ApacheDockerHttpClient$Builder.)
                        (.dockerHost (URI. docker-uri))
                        (.sslConfig (.getSSLConfig config))
                        (.build))]
    (DockerClientImpl/getInstance config http-client)))

;; ---------------------------------------------------------------------------
;; Container inspection → Resource
;; ---------------------------------------------------------------------------

(defn- label [container key]
  (get (.getLabels container) key))

(defn- container-ip
  "Extract the container IP on smithr-network."
  [container network-name]
  (some-> (.getNetworkSettings container)
          (.getNetworks)
          (get network-name)
          (.getIpAddress)))

(defn- build-connection
  "Build the connection map for a resource based on its platform and labels.
   host-port-fn: (container-port) → mapped host port or nil."
  [platform labels host-address ip remote? host-port-fn]
  (let [connect-host (get labels "smithr.resource.connect-host")
        connect-port (some-> (get labels "smithr.resource.connect-port") Integer/parseInt)
        serial (get labels "smithr.resource.serial")]
    (case platform
      :android (cond-> {:adb-host (or connect-host
                                      (if (and remote? (host-port-fn 5555))
                                        host-address ip))
                        :adb-port (or connect-port
                                      (if (and remote? (host-port-fn 5555))
                                        (host-port-fn 5555) 5555))}
                 serial (assoc :serial serial)
                 (host-port-fn 5900) (assoc :vnc-port (host-port-fn 5900)))
      :ios (let [physical? (= "physical" (get labels "smithr.resource.substrate"))
                 ;; Physical iOS: bridge container's sshd on Docker IP, port 22.
                 ;; Simulated iOS: parent macOS VM via QEMU port 10022.
                 ssh-host (if physical?
                            ip
                            (or connect-host
                                (if (and remote? (host-port-fn 10022))
                                  host-address ip)))
                 ssh-port (if physical?
                            22
                            (or connect-port
                                (if (and remote? (host-port-fn 10022))
                                  (host-port-fn 10022) 10022)))]
              (cond-> {:ssh-host ssh-host :ssh-port ssh-port}
                (get labels "smithr.resource.udid")
                (assoc :udid (get labels "smithr.resource.udid"))
                (get labels "smithr.resource.rsd-port")
                (assoc :rsd-port (Integer/parseInt
                                   (get labels "smithr.resource.rsd-port")))))
      :macos (cond-> {:ssh-host (if (and remote? (host-port-fn 10022))
                                   host-address ip)
                       :ssh-port (if (and remote? (host-port-fn 10022))
                                   (host-port-fn 10022) 10022)}
                (host-port-fn 5999) (assoc :vnc-port (host-port-fn 5999)))
      :android-build {:ssh-host (if (and remote? (host-port-fn 22))
                                   host-address ip)
                       :ssh-port (if (and remote? (host-port-fn 22))
                                   (host-port-fn 22) 22)}
      ;; Default: direct-access resources (e.g. servers)
      (cond-> {:container-ip ip}
        remote? (assoc :ssh-host host-address)
        (get labels "smithr.resource.service-port")
        (assoc :service-port (Integer/parseInt (get labels "smithr.resource.service-port")))))))

(defn- build-resource
  "Build a resource map from extracted container metadata."
  [{:keys [labels container-name host-label host-address ip
           running? health host-port-fn]}]
  (let [res-type (keyword (get labels "smithr.resource.type"))
        platform (keyword (get labels "smithr.resource.platform"))
        id (str host-label ":" (clojure.core/name platform) ":" container-name)
        status (cond (not running?) :dead (= health "healthy") :warm :else :booting)
        remote? (some? host-address)
        connection (build-connection platform labels host-address ip remote? host-port-fn)]
    (cond-> {:id id
             :type res-type
             :platform platform
             :host host-label
             :status status
             :container container-name
             :connection (assoc connection :metrics-port
                                (if-let [mp (get labels "smithr.resource.metrics-port")]
                                  (Integer/parseInt mp)
                                  (case platform :macos 10100 9100)))
             :parent (get labels "smithr.resource.parent")
             :substrate (get labels "smithr.resource.substrate")
             :model (get labels "smithr.resource.model")
             :device-name (get labels "smithr.resource.device-name")
             :provisioned? (= "true" (get labels "smithr.provisioned"))
             :updated-at (Instant/now)}
      (#{:macos :android-build} platform)
      (assoc :max-slots (if-let [s (get labels "smithr.resource.max-slots")]
                          (Integer/parseInt s)
                          10)
             :active-leases #{}))))

(defn container->resource
  "Convert a Docker container inspect result to a Resource map.
   host-address is the reachable hostname/IP for remote hosts (nil for local)."
  [container host-label network-name host-address]
  (let [labels (.getLabels (.getConfig container))
        name (-> (.getName container) (subs 1))
        ip (some-> (.getNetworkSettings container)
                   (.getNetworks)
                   (get network-name)
                   (.getIpAddress))
        state-obj (.getState container)
        port-bindings (some-> (.getNetworkSettings container)
                              (.getPorts)
                              (.getBindings))
        host-port-fn (fn [container-port]
                       (some-> port-bindings
                               (get (com.github.dockerjava.api.model.ExposedPort/tcp container-port))
                               first
                               (.getHostPortSpec)
                               (Integer/parseInt)))]
    (build-resource {:labels labels
                     :container-name name
                     :host-label host-label
                     :host-address host-address
                     :ip ip
                     :running? (.getRunning state-obj)
                     :health (some-> (.getHealth state-obj) (.getStatus))
                     :host-port-fn host-port-fn})))

(defn- inspect-via-cli
  "Fallback: inspect a container via Docker CLI and build a resource map.
   Used when docker-java inspect fails (e.g. unrecognized CAP_ enum values)."
  [container-id host-label network-name host-address]
  (try
    (let [{:keys [exit out]} (shell/sh "docker" "inspect" container-id)]
      (when (zero? exit)
        (let [data (first (json/read-str out))
              labels (get-in data ["Config" "Labels"])
              name (let [n (get data "Name")] (if (.startsWith ^String n "/") (subs n 1) n))
              ip (get-in data ["NetworkSettings" "Networks" network-name "IPAddress"])
              state-map (get data "State")
              ports (get-in data ["NetworkSettings" "Ports"])
              host-port-fn (fn [cp]
                             (some-> (get ports (str cp "/tcp"))
                                     first
                                     (get "HostPort")
                                     Integer/parseInt))]
          (build-resource {:labels labels
                           :container-name name
                           :host-label host-label
                           :host-address host-address
                           :ip ip
                           :running? (get state-map "Running")
                           :health (get-in state-map ["Health" "Status"])
                           :host-port-fn host-port-fn}))))
    (catch Exception e
      (log/warn "CLI fallback inspect failed for" container-id ":" (.getMessage e))
      nil)))

(defn- safe-inspect
  "Inspect a container via docker-java, falling back to CLI on failure.
   docker-java 3.4.x can't deserialize CAP_ prefixed capabilities — CLI
   fallback handles any container configuration that docker-java chokes on."
  [client container-id host-label network-name host-address]
  (try
    (let [inspected (-> client (.inspectContainerCmd container-id) (.exec))]
      (container->resource inspected host-label network-name host-address))
    (catch Exception e
      (log/debug "docker-java inspect failed for" container-id "- trying CLI fallback")
      (or (inspect-via-cli container-id host-label network-name host-address)
          (do (log/warn "Both docker-java and CLI inspect failed for" container-id
                        ":" (.getMessage e))
              nil)))))

;; ---------------------------------------------------------------------------
;; Container lookup (for adopt protocol)
;; ---------------------------------------------------------------------------

(defn find-container
  "Find a container by name across all connected Docker hosts.
   Returns {:container inspected, :host-label label, :host-address addr, :network-name net}
   or nil if not found."
  [container-name]
  (some (fn [[host-label {:keys [client host-address network-name]}]]
          (try
            (let [inspected (-> client (.inspectContainerCmd container-name) (.exec))]
              {:container    inspected
               :host-label   host-label
               :host-address host-address
               :network-name network-name})
            (catch com.github.dockerjava.api.exception.NotFoundException _
              nil)
            (catch Exception e
              (log/debug "Error inspecting" container-name "on" host-label ":" (.getMessage e))
              nil)))
        @docker-clients))

(defn container-ip-any-network
  "Get the IP address from the first available Docker network on a container.
   Adopted containers may not be on smithr-network, so we check all networks."
  [container]
  (some-> (.getNetworkSettings container)
          (.getNetworks)
          vals
          first
          (.getIpAddress)))

;; ---------------------------------------------------------------------------
;; Initial container scan
;; ---------------------------------------------------------------------------

(defn- attach-workers!
  "Look up worker containers (labelled smithr.worker-for=<phone-container>)
   and attach their SSH endpoint to the corresponding phone resource's connection map.
   Workers provide near-side SSH access for colocated tool execution."
  [client network-name]
  (try
    (let [workers (-> client
                      (.listContainersCmd)
                      (.withLabelFilter {"smithr.worker-for" ""})
                      (.exec))]
      (doseq [w workers]
        (let [labels   (into {} (.getLabels w))
              worker-for (get labels "smithr.worker-for")
              worker-ip  (some-> (.getNetworkSettings w)
                                 (.getNetworks)
                                 (get network-name)
                                 (.getIpAddress))
              running?   (= "running" (.getState w))]
          (when (and worker-for worker-ip running?)
            (when-let [resource (first (filter #(= (:container %) worker-for)
                                               (state/resources)))]
              (log/info "Attaching worker" worker-for "→" worker-ip ":22 to" (:id resource))
              (state/upsert-resource!
                (update resource :connection assoc
                        :worker-ssh-host worker-ip
                        :worker-ssh-port 22)))))))
    (catch Exception e
      (log/warn "Failed to discover workers:" (.getMessage e)))))

(defn scan-containers!
  "Scan running containers for smithr.managed=true and register them."
  [client host-label network-name host-address]
  (log/info "Scanning containers on" host-label)
  (let [containers (-> client
                       (.listContainersCmd)
                       (.withLabelFilter {"smithr.managed" "true"})
                       (.withShowAll true)
                       (.exec))]
    (doseq [c containers]
      (let [id (.getId c)]
        (when-let [resource (safe-inspect client id host-label network-name host-address)]
          (log/info "Discovered resource:" (:id resource) "status:" (:status resource))
          (state/upsert-resource! resource))))
    (attach-workers! client network-name)
    (log/info "Scan complete on" host-label "- found" (count containers) "managed containers")))

;; ---------------------------------------------------------------------------
;; Event subscription
;; ---------------------------------------------------------------------------

(defn- handle-event
  "Process a Docker event and update state."
  [client host-label network-name host-address ^Event event]
  (let [action (.getAction event)
        actor (.getActor event)
        attrs (when actor (.getAttributes actor))
        managed? (= "true" (get attrs "smithr.managed"))
        container-id (when actor (.getId actor))]
    ;; Check adopted containers on die/destroy (even if not smithr.managed)
    (when (and (not managed?) container-id
               (contains? #{"die" "destroy"} action))
      (when-let [adopt (state/adopt-by-container-id container-id)]
        (log/info "Adopted container" (:container-name adopt) "event:" action "- cleaning up")
        (try
          ;; Force-unlease any active lease on the adopted resource first
          (let [resource-id (str "adopted:" (:container-name adopt))
                active-lease (first (filter #(= (:resource-id %) resource-id)
                                            (state/leases)))]
            (when active-lease
              (log/info "Force-unleasing" (:id active-lease) "for dying adopted container")
              ((requiring-resolve 'smithr.lease/unlease!) (:id active-lease))))
          ((requiring-resolve 'smithr.lease/unadopt!) (:id adopt))
          (catch Exception e
            (log/error e "Error cleaning up adopted container" (:id adopt))))))
    ;; Handle worker container events (not smithr.managed, but smithr.worker-for)
    (when (and (not managed?) container-id
               (= action "start")
               (get attrs "smithr.worker-for"))
      (let [worker-for (get attrs "smithr.worker-for")]
        (log/info "Worker container started for" worker-for "- attaching SSH endpoint")
        ;; Small delay to let networking initialize
        (future
          (Thread/sleep 2000)
          (try
            (let [[_ {:keys [client network-name]}]
                  (first (filter (fn [[k _]] (= k host-label)) @docker-clients))]
              (when client
                (attach-workers! client network-name)))
            (catch Exception e
              (log/warn "Failed to attach worker on start:" (.getMessage e)))))))
    (when (and managed?
               ;; Skip exec events (healthchecks etc) — too noisy
               (not (.startsWith (or action "") "exec_"))
               container-id)
      (log/debug "Docker event:" action "container:" container-id "on" host-label)
      (let [container-id container-id]
        (case action
          ;; Container started — inspect and register
          "start"
          (when-let [resource (safe-inspect client container-id host-label network-name host-address)]
            ;; Invalidate cached workspace users — VM rebooted, overlay is fresh
            (state/invalidate-workspaces-for-resource! (:id resource))
            (state/upsert-resource! resource))

          ;; Health status changed
          "health_status: healthy"
          (when-let [resource (safe-inspect client container-id host-label network-name host-address)]
            ;; Only mark warm if not currently leased
            (if (#{:leased :shared} (:status (state/resource (:id resource))))
              (log/debug "Container healthy but leased/shared, keeping current status")
              (state/upsert-resource! resource)))

          ;; Container died or was destroyed — remove resource
          ("die" "destroy")
          (let [;; Find matching resource by container ID prefix
                matching (filter #(or (.contains (:container %) container-id)
                                      (.startsWith container-id (:container %)))
                                 (state/resources))]
            ;; Also try full name lookup
            (doseq [r (state/resources)]
              (when (= container-id (:container r))
                (log/info "Removing resource" (:id r) "due to" action)
                (state/remove-resource! (:id r))))
            ;; Mark by container-id if name lookup didn't match
            (when (empty? matching)
              (log/debug "No resource found for container" container-id)))

          ;; Ignore other events
          nil)))))

(defn subscribe-events!
  "Subscribe to Docker events for managed containers.
   Returns the callback (closeable) for cleanup."
  [client host-label network-name host-address]
  (log/info "Subscribing to Docker events on" host-label)
  (let [callback (proxy [EventsResultCallback] []
                   (onNext [^Event event]
                     (try
                       (handle-event client host-label network-name host-address event)
                       (catch Exception e
                         (log/error e "Error handling Docker event")))))]
    (-> client
        (.eventsCmd)
        (.withEventTypeFilter (into-array EventType [EventType/CONTAINER]))
        (.exec callback))
    callback))

;; ---------------------------------------------------------------------------
;; Host lifecycle
;; ---------------------------------------------------------------------------

(defonce ^:private host-tunnels
  (atom {}))  ;; label -> {:port int, :host-address string}

(def ^:private tunnel-base-port
  "Base port for auto-allocated Docker host tunnels."
  12375)

(defonce ^:private next-tunnel-port
  (atom tunnel-base-port))

(defn- allocate-tunnel-port! []
  (let [p @next-tunnel-port]
    (swap! next-tunnel-port inc)
    p))

(defn- resolve-docker-uri
  "Resolve the Docker URI for a host config.
   - Local hosts (no :host-address): uses docker-uri or default unix socket.
   - Remote hosts with docker-uri: uses it directly.
   - Remote hosts with TLS: constructs tcp://<host-address>:2376.
   - Remote hosts without docker-uri or TLS: auto-creates SSH tunnel."
  [{:keys [label docker-uri host-address tls]}]
  (cond
    ;; Local host — use provided URI or default unix socket
    (not host-address)
    (or docker-uri "unix:///var/run/docker.sock")

    ;; Remote host with explicit docker-uri — use it directly
    docker-uri
    (do (log/info "Using configured Docker URI for" label ":" docker-uri)
        docker-uri)

    ;; Remote host with TLS — connect directly, no tunnel needed
    tls
    (let [port (get tls :port 2376)
          uri (str "tcp://" host-address ":" port)]
      (log/info "Using TLS connection to" label "at" uri)
      uri)

    ;; Remote host, no docker-uri, no TLS — auto-create SSH tunnel
    :else
    (if-let [existing (get @host-tunnels label)]
      (let [uri (str "tcp://localhost:" (:port existing))]
        (log/debug "SSH tunnel to" label "already exists on port" (:port existing))
        uri)
      (let [port (allocate-tunnel-port!)]
        (log/info "Creating SSH tunnel to" label
                  "(" host-address " → localhost:" port ")")
        (let [result (shell/sh "ssh" "-fNL"
                       (str port ":/var/run/docker.sock")
                       "-o" "StrictHostKeyChecking=no"
                       "-o" "BatchMode=yes"
                       "-o" "ServerAliveInterval=30"
                       "-o" "ServerAliveCountMax=3"
                       "-o" "ExitOnForwardFailure=yes"
                       "-o" "ConnectTimeout=10"
                       host-address)]
          (if (zero? (:exit result))
            (let [uri (str "tcp://localhost:" port)]
              (log/info "SSH tunnel to" label "established on port" port)
              (swap! host-tunnels assoc label {:port port :host-address host-address})
              (Thread/sleep 500)
              uri)
            (do
              (log/error "Failed to create SSH tunnel to" label
                         "exit:" (:exit result) "err:" (:err result))
              nil)))))))

(defn connect-host!
  "Connect to a Docker host: register, scan containers, subscribe to events.
   Supports three remote connection modes:
     1. TLS (preferred): set :tls {:cert-path \"/etc/smithr/tls\"} on host config
     2. Explicit URI: set :docker-uri on host config
     3. SSH tunnel (auto): just set :host-address, tunnel created automatically
   Returns {:client client :subscription subscription}."
  [host-config network-name]
  (let [{:keys [label host-address tls]} host-config
        docker-uri (resolve-docker-uri host-config)]
    (if-not docker-uri
      (do
        (log/error "Cannot connect to" label "— connection setup failed")
        (state/register-host! label "failed")
        (state/disconnect-host! label)
        nil)
      (do
        (log/info "Connecting to Docker host" label "at" docker-uri
                  (when host-address (str "(remote: " host-address ")"))
                  (when tls " [TLS]"))
        (try
          (let [client (if tls
                         (make-client docker-uri
                                      :tls-verify true
                                      :cert-path (:cert-path tls))
                         (make-client docker-uri))]
            (-> client (.pingCmd) (.exec))
            (log/info "Connected to" label)
            (state/register-host! label docker-uri)
            ;; Register client for cross-host container lookup
            (swap! docker-clients assoc label
                   {:client client :host-address host-address :network-name network-name})
            (scan-containers! client label network-name host-address)
            (let [sub (subscribe-events! client label network-name host-address)]
              {:client client :subscription sub :label label}))
          (catch Exception e
            (log/error "Failed to connect to" label ":" (.getMessage e))
            (state/register-host! label docker-uri)
            (state/disconnect-host! label)
            nil))))))
