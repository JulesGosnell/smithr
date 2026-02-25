(ns smithr.docker
  "Docker event subscription and container inspection.
   Uses docker-java to listen for container lifecycle events
   filtered by smithr.managed=true label."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :as shell]
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

(defn container->resource
  "Convert a Docker container inspect result to a Resource map.
   host-address is the reachable hostname/IP for remote hosts (nil for local)."
  [container host-label network-name host-address]
  (let [labels (.getLabels (.getConfig container))
        res-type (keyword (get labels "smithr.resource.type"))
        platform (keyword (get labels "smithr.resource.platform"))
        name (-> (.getName container) (subs 1)) ;; strip leading /
        ip (some-> (.getNetworkSettings container)
                   (.getNetworks)
                   (get network-name)
                   (.getIpAddress))
        id (str host-label ":" (clojure.core/name platform) ":" name)
        state-obj (.getState container)
        running? (.getRunning state-obj)
        health (some-> (.getHealth state-obj) (.getStatus))
        status (cond
                 (not running?) :dead
                 (= health "healthy") :warm
                 :else :booting)
        ;; Extract host port bindings for VNC
        port-bindings (some-> (.getNetworkSettings container)
                              (.getPorts)
                              (.getBindings))
        host-port-for (fn [container-port]
                        (some-> port-bindings
                                (get (com.github.dockerjava.api.model.ExposedPort/tcp container-port))
                                first
                                (.getHostPortSpec)
                                (Integer/parseInt)))
        ;; Build connection map based on platform
        ;; For remote hosts, use host-address + host-mapped ports instead of container IPs
        remote? (some? host-address)
        connection (case platform
                     :android (cond-> {:adb-host (if (and remote? (host-port-for 5555))
                                                   host-address ip)
                                       :adb-port (if (and remote? (host-port-for 5555))
                                                   (host-port-for 5555) 5555)}
                                (host-port-for 5900) (assoc :vnc-port (host-port-for 5900)))
                     :ios (let [;; iOS sidecar doesn't run SSH — use connect labels pointing to macOS VM
                               connect-host (get labels "smithr.resource.connect-host")
                               connect-port (some-> (get labels "smithr.resource.connect-port")
                                                    Integer/parseInt)
                               ssh-host (or connect-host
                                            (if (and remote? (host-port-for 10022))
                                              host-address ip))
                               ssh-port (or connect-port
                                            (if (and remote? (host-port-for 10022))
                                              (host-port-for 10022) 10022))]
                            {:ssh-host ssh-host :ssh-port ssh-port})
                     :macos (cond-> {:ssh-host (if (and remote? (host-port-for 10022))
                                                 host-address ip)
                                     :ssh-port (if (and remote? (host-port-for 10022))
                                                 (host-port-for 10022) 10022)}
                              (host-port-for 5999) (assoc :vnc-port (host-port-for 5999)))
                     :android-build {:ssh-host (if (and remote? (host-port-for 22))
                                                 host-address ip)
                                     :ssh-port (if (and remote? (host-port-for 22))
                                                 (host-port-for 22) 22)}
                     ;; Default: store container IP for direct-access resources (e.g. servers)
                     (cond-> {:container-ip ip}
                       (get labels "smithr.resource.service-port")
                       (assoc :service-port (Integer/parseInt (get labels "smithr.resource.service-port")))))]
    (cond-> {:id id
             :type res-type
             :platform platform
             :host host-label
             :status status
             :container name
             :connection (cond-> connection
                           true (assoc :metrics-port
                                       (if-let [mp (get labels "smithr.resource.metrics-port")]
                                         (Integer/parseInt mp)
                                         (case platform
                                           :macos 10100
                                           9100))))
             :parent (get labels "smithr.resource.parent")
             :substrate (get labels "smithr.resource.substrate")
             :model (get labels "smithr.resource.model")
             :provisioned? (= "true" (get labels "smithr.provisioned"))
             :updated-at (Instant/now)}
      (#{:macos :android-build} platform)
      (assoc :max-slots (if-let [s (get labels "smithr.resource.max-slots")]
                          (Integer/parseInt s)
                          10)
             :active-leases #{}))))

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
      (let [id (.getId c)
            inspected (-> client (.inspectContainerCmd id) (.exec))
            resource (container->resource inspected host-label network-name host-address)]
        (log/info "Discovered resource:" (:id resource) "status:" (:status resource))
        (state/upsert-resource! resource)))
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
    (when (and managed?
               ;; Skip exec events (healthchecks etc) — too noisy
               (not (.startsWith (or action "") "exec_"))
               container-id)
      (log/debug "Docker event:" action "container:" container-id "on" host-label)
      (let [container-id container-id]
        (case action
          ;; Container started — inspect and register
          "start"
          (try
            (let [inspected (-> client (.inspectContainerCmd container-id) (.exec))
                  resource (container->resource inspected host-label network-name host-address)]
              ;; Invalidate cached workspace users — VM rebooted, overlay is fresh
              (state/invalidate-workspaces-for-resource! (:id resource))
              (state/upsert-resource! resource))
            (catch Exception e
              (log/warn "Failed to inspect container on start:" (.getMessage e))))

          ;; Health status changed
          "health_status: healthy"
          (let [inspected (-> client (.inspectContainerCmd container-id) (.exec))
                resource (container->resource inspected host-label network-name host-address)]
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
