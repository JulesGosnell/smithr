(ns hammar.docker
  "Docker event subscription and container inspection.
   Uses docker-java to listen for container lifecycle events
   filtered by smithr.managed=true label."
  (:require [clojure.tools.logging :as log]
            [hammar.state :as state])
  (:import [com.github.dockerjava.core DockerClientImpl DefaultDockerClientConfig]
           [com.github.dockerjava.core.command EventsResultCallback]
           [com.github.dockerjava.httpclient5 ApacheDockerHttpClient$Builder]
           [com.github.dockerjava.api.model Event EventType]
           [java.net URI]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Docker client creation
;; ---------------------------------------------------------------------------

(defn make-client
  "Create a Docker client for the given URI (unix or tcp)."
  [docker-uri]
  (let [config (-> (DefaultDockerClientConfig/createDefaultConfigBuilder)
                   (.withDockerHost docker-uri)
                   (.build))
        http-client (-> (ApacheDockerHttpClient$Builder.)
                        (.dockerHost (URI. docker-uri))
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
                     {})]
    (cond-> {:id id
             :type res-type
             :platform platform
             :host host-label
             :status status
             :container name
             :connection connection
             :parent (get labels "smithr.resource.parent")
             :updated-at (Instant/now)}
      (= platform :macos)
      (assoc :max-slots (if-let [s (get labels "smithr.resource.max-slots")]
                          (Integer/parseInt s)
                          10)
             :active-leases #{}))))

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
        managed? (= "true" (get attrs "smithr.managed"))]
    (when managed?
      (let [container-id (.getId actor)]
        (log/debug "Docker event:" action "container:" container-id "on" host-label)
        (case action
          ;; Container started — inspect and register
          "start"
          (try
            (let [inspected (-> client (.inspectContainerCmd container-id) (.exec))
                  resource (container->resource inspected host-label network-name host-address)]
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

(defn connect-host!
  "Connect to a Docker host: register, scan containers, subscribe to events.
   Returns {:client client :subscription subscription}."
  [host-config network-name]
  (let [{:keys [label docker-uri host-address]} host-config]
    (log/info "Connecting to Docker host" label "at" docker-uri
              (when host-address (str "(host-address: " host-address ")")))
    (try
      (let [client (make-client docker-uri)]
        ;; Verify connectivity
        (-> client (.pingCmd) (.exec))
        (log/info "Connected to" label)
        (state/register-host! label docker-uri)
        (scan-containers! client label network-name host-address)
        (let [sub (subscribe-events! client label network-name host-address)]
          {:client client :subscription sub :label label}))
      (catch Exception e
        (log/error "Failed to connect to" label ":" (.getMessage e))
        (state/register-host! label docker-uri)
        (state/disconnect-host! label)
        nil))))
