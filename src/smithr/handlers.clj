;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.handlers
  "Ring handler implementations for the Smithr API."
  (:require [smithr.state :as state]
            [smithr.lease :as lease]
            [smithr.metrics :as metrics]
            [smithr.provision :as provision]
            [smithr.devices :as devices]
            [smithr.templates :as templates]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))

(def ^:private git-hash
  "Git commit hash captured at load time."
  (try
    (-> (shell/sh "git" "rev-parse" "--short" "HEAD")
        :out str/trim)
    (catch Exception _ "unknown")))

(def ^:private started-at
  "Startup timestamp captured at load time."
  (java.time.Instant/now))

(def ^:private lan-ip
  "LAN IP of this host, resolved at load time. Used in lease responses so
   clients (especially containers) can reach tunnel ports without resolving hostnames."
  (try
    (let [sock (java.net.DatagramSocket.)]
      (.connect sock (java.net.InetAddress/getByName "8.8.8.8") 53)
      (let [ip (.getHostAddress (.getLocalAddress sock))]
        (.close sock)
        ip))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Response helpers
;; ---------------------------------------------------------------------------

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status
    :body   body}))

(defn- not-found [msg]
  {:status 404
   :body   {:error "not_found" :message msg}})

(defn- conflict [msg]
  {:status 409
   :body   {:error "conflict" :message msg}})

;; ---------------------------------------------------------------------------
;; Serialization helpers
;; ---------------------------------------------------------------------------

(defn- serialize-instant [^Instant inst]
  (when inst (str inst)))

(defn- kw->underscore
  "Convert a keyword map to underscore-string keys for JSON.
   Handles non-keyword keys (e.g., integer keys in port maps) gracefully."
  [m]
  (into {}
        (map (fn [[k v]]
               [(if (keyword? k)
                  (-> (name k) (clojure.string/replace "-" "_") keyword)
                  k)
                (if (map? v) (kw->underscore v) v)]))
        m))

(defn- serialize-resource [r]
  (-> r
      (update :type name)
      (update :platform name)
      (update :status name)
      (update :updated-at serialize-instant)
      ;; For macOS VMs: expose slot info, remove internal set
      (cond->
        (:max-slots r) (assoc :active-lease-count (count (:active-leases r #{})))
        ;; Expose provisioned flag
        true (assoc :provisioned (:provisioned? r false))
        true (dissoc :active-leases :provisioned?))
      kw->underscore))

(defn- serialize-lease [l]
  (-> l
      (update :acquired-at serialize-instant)
      (update :expires-at serialize-instant)
      (cond->
        (:lease-type l) (update :lease-type name)
        lan-ip (assoc :tunnel-host lan-ip))
      kw->underscore))

(defn- serialize-host [h]
  (let [resource-count (->> (state/resources)
                            (filter #(= (:host %) (:label h)))
                            count)]
    {:label          (:label h)
     :docker_uri     (:docker-uri h)
     :connected      (:connected? h)
     :resource_count resource-count}))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn list-resources
  "GET /api/resources"
  [request]
  (let [params     (:query-params request)
        type-f     (get params "type")
        platform-f (get params "platform")
        status-f   (get params "status")
        host-f     (get params "host")
        resources  (cond->> (state/resources)
                     type-f     (filter #(= (name (:type %)) type-f))
                     platform-f (filter #(= (name (:platform %)) platform-f))
                     status-f   (filter #(= (name (:status %)) status-f))
                     host-f     (filter #(= (:host %) host-f)))]
    (json-response (mapv serialize-resource resources))))

(defn get-resource
  "GET /api/resources/:id"
  [request]
  (let [id (get-in request [:path-params :id])]
    (if-let [resource (state/resource id)]
      (json-response (serialize-resource resource))
      (not-found (str "Resource not found: " id)))))

(defn list-leases
  "GET /api/leases"
  [request]
  (let [params  (:query-params request)
        lessee  (get params "lessee")
        leases  (cond->> (state/leases)
                  lessee (filter #(= (:lessee %) lessee)))]
    (json-response (mapv serialize-lease leases))))

(defn get-lease
  "GET /api/leases/:id"
  [request]
  (let [id (get-in request [:path-params :id])]
    (if-let [l (state/lease id)]
      (json-response (serialize-lease l))
      (not-found (str "Lease not found: " id)))))

(defn acquire-lease
  "POST /api/leases"
  [request]
  (let [body      (:body-params request)
        workspace (or (:workspace body) (get body "workspace"))
        server-ports (or (:server_ports body) (get body "server_ports"))
        reverse-ports (or (:reverse_ports body) (get body "reverse_ports"))
        substrate (or (:substrate body) (get body "substrate"))
        model (or (:model body) (get body "model"))
        tunnel-protocol (or (:tunnel_protocol body) (get body "tunnel_protocol"))
        params    (cond-> {:type        (or (:type body) (get body "type"))
                           :platform    (or (:platform body) (get body "platform"))
                           :ttl-seconds (or (:ttl_seconds body) (get body "ttl_seconds") 1800)
                           :lessee      (or (:lessee body) (get body "lessee") "anonymous")
                           :lease-type  (keyword (or (:lease_type body) (get body "lease_type") "phone"))}
                    workspace (assoc :workspace workspace)
                    substrate (assoc :substrate substrate)
                    model (assoc :model model)
                    tunnel-protocol (assoc :tunnel-protocol tunnel-protocol)
                    (seq server-ports) (assoc :server-ports (vec server-ports))
                    (seq reverse-ports) (assoc :reverse-ports (vec reverse-ports)))]
    (log/info "Lease request:" params)
    (if (or (nil? (:type params)) (nil? (:platform params)))
      {:status 400
       :body   {:error "bad_request"
                :message (str "Missing required field(s):"
                              (when (nil? (:type params)) " type")
                              (when (nil? (:platform params)) " platform"))}}
      (try
        (if-let [result (lease/acquire! params)]
          (json-response 201 (serialize-lease result))
          (conflict (str "No available " (:type params) ":" (:platform params) " resource")))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (if (:lease-id data)
              (conflict (str "Workspace '" (:workspace data) "' is already leased"))
              {:status 400
               :body   {:error "bad_request"
                        :message (.getMessage e)}})))))))

(defn unlease
  "DELETE /api/leases/:id"
  [request]
  (let [id (get-in request [:path-params :id])]
    (if (lease/unlease! id)
      {:status 204 :body nil}
      (not-found (str "Lease not found: " id)))))

(defn list-hosts
  "GET /api/hosts"
  [_request]
  (json-response (mapv serialize-host (state/hosts))))

(defn health-check
  "GET /api/health"
  [_request]
  (let [hosts      (state/hosts)
        resources  (state/resources)
        leases     (state/leases)
        workspaces (state/workspaces)
        adopts     (state/adopts)
        all-connected? (every? :connected? hosts)]
    (json-response {:status     (if all-connected? "ok" "degraded")
                    :hosts      (count hosts)
                    :resources  (count resources)
                    :leases     (count leases)
                    :workspaces (count workspaces)
                    :adopts     (count adopts)
                    :git-hash   git-hash
                    :started-at (str started-at)})))

(defn list-metrics
  "GET /api/metrics"
  [_request]
  (json-response (metrics/metrics-snapshot)))

;; ---------------------------------------------------------------------------
;; Workspace handlers
;; ---------------------------------------------------------------------------

(defn- serialize-workspace [ws]
  (-> ws
      (update :status name)
      (update :created-at (fn [t] (when t (str t))))
      kw->underscore))

(defn list-workspaces
  "GET /api/workspaces"
  [_request]
  (json-response (mapv serialize-workspace (state/workspaces))))

(defn get-workspace
  "GET /api/workspaces/:name"
  [request]
  (let [ws-name (get-in request [:path-params :name])]
    (if-let [ws (state/workspace ws-name)]
      (json-response (serialize-workspace ws))
      (not-found (str "Workspace not found: " ws-name)))))

(defn purge-workspace
  "DELETE /api/workspaces/:name"
  [request]
  (let [ws-name (get-in request [:path-params :name])]
    (if-let [ws (state/workspace ws-name)]
      (if (= (:status ws) :leased)
        (conflict (str "Workspace '" ws-name "' is currently leased — unlease it first"))
        (do
          (lease/purge-workspace! ws-name)
          {:status 204 :body nil}))
      (not-found (str "Workspace not found: " ws-name)))))

(defn list-events
  "GET /api/events"
  [request]
  (let [params (:query-params request)
        limit  (some-> (get params "limit") parse-long)
        events (if limit
                 (state/events limit)
                 (state/events))]
    (json-response (vec events))))

;; ---------------------------------------------------------------------------
;; Adopt handlers
;; ---------------------------------------------------------------------------

(defn- serialize-adopt [a]
  (-> a
      (update :adopted-at serialize-instant)
      (update :expires-at serialize-instant)
      kw->underscore))

(defn adopt-container
  "POST /api/adopt"
  [request]
  (let [body   (:body-params request)
        params (cond-> {:container-name    (or (:container_name body) (get body "container_name"))
                        :ports             (vec (or (:ports body) (get body "ports")))
                        :lessee            (or (:lessee body) (get body "lessee") "anonymous")
                        :ttl-seconds       (or (:ttl_seconds body) (get body "ttl_seconds") 3600)}
                 (or (:resource_type body) (get body "resource_type"))
                 (assoc :resource-type (or (:resource_type body) (get body "resource_type")))
                 (or (:resource_platform body) (get body "resource_platform"))
                 (assoc :resource-platform (or (:resource_platform body) (get body "resource_platform")))
                 (or (:max_slots body) (get body "max_slots"))
                 (assoc :max-slots (or (:max_slots body) (get body "max_slots"))))]
    (log/info "Adopt request:" params)
    (try
      (let [result (lease/adopt! params)]
        (json-response 201 (serialize-adopt result)))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            (:not-found data)
            (not-found (str "Container not found: " (:container-name params)))

            :else
            {:status 500
             :body   {:error "adopt_failed"
                      :message (.getMessage e)}}))))))

(defn unadopt
  "DELETE /api/adopts/:id"
  [request]
  (let [id (get-in request [:path-params :id])]
    (try
      (if (lease/unadopt! id)
        {:status 204 :body nil}
        (not-found (str "Adopt not found: " id)))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (:has-lease data)
            (conflict (.getMessage e))
            {:status 500 :body {:error "unadopt_failed" :message (.getMessage e)}}))))))

(defn list-adopts
  "GET /api/adopts"
  [_request]
  (json-response (mapv serialize-adopt (state/adopts))))

(defn serve-openapi
  "GET /openapi.yaml"
  [_request]
  (let [spec (slurp (io/resource "openapi.yaml"))]
    {:status  200
     :headers {"Content-Type" "text/yaml; charset=utf-8"}
     :body    spec}))

;; ---------------------------------------------------------------------------
;; Compose template handler
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Catalogue + device scan handlers
;; ---------------------------------------------------------------------------

(defn catalogue
  "GET /api/catalogue — list provisionable device variants and active resources."
  [_request]
  (if-let [cat (provision/catalogue)]
    (json-response cat)
    (json-response {:variants [] :active_resources []})))

(defn provision-resource
  "POST /api/provision — spin up a new resource from a catalogue template.
   Does NOT create a lease — just provisions the container for later use.
   Optional 'model' param selects a device variant (e.g. 'Pixel 3a')."
  [request]
  (let [body (:body-params request)
        template-key (or (:template body) (get body "template"))
        model (or (:model body) (get body "model"))]
    (if-not template-key
      {:status 400
       :body {:error "bad_request" :message "Missing 'template' field"}}
      (if-not (provision/provisioning-enabled?)
        (conflict "Provisioning not configured")
        (let [tmpl (provision/get-template template-key)]
          (if-not tmpl
            (not-found (str "Unknown template: " template-key))
            (let [cat-info (:catalogue tmpl)
                  type (:type cat-info)
                  platform (:platform cat-info)
                  device-env (when model (provision/lookup-variant template-key model))]
              (log/info "Provision request: template=" template-key "model=" model)
              (future
                (try
                  (provision/ensure-resource! {:type type :platform platform
                                              :device-env device-env})
                  (catch Exception e
                    (log/error e "Provisioning failed for" template-key))))
              (json-response 202 {:status "provisioning"
                                  :template template-key
                                  :model model
                                  :message (str "Provisioning " template-key
                                                (when model (str " (" model ")"))
                                                " — check dashboard for progress")}))))))))

(defn scan-devices
  "GET /api/scan/devices — scan local host for connected USB devices."
  [_request]
  (let [host-label (.. java.net.InetAddress getLocalHost getHostName)
        result (devices/scan-all-devices host-label)]
    (json-response result)))

;; ---------------------------------------------------------------------------
;; Compose template handler
;; ---------------------------------------------------------------------------

(def ^:private static-templates
  #{"android-phone" "ios-phone" "macos-build" "android-build" "sandbox"
    "maestro" "android-app" "ios-app"
    "adopt-proxy" "server" "playwright" "metro"})

(defn- resolve-registry-url []
  [(or (System/getenv "SMITHR_REGISTRY") "localhost:5000")
   (or (System/getenv "SMITHR_URL") "http://10.21.0.1:7070")])

(defn serve-compose-template
  "GET /api/compose/:template — serve a ready-to-use compose YAML for a resource type.
   Checks static templates first, then dynamic (published) templates.
   Substitutes {{REGISTRY}} and {{SMITHR_URL}} with the current host's values."
  [request]
  (let [template (get-in request [:path-params :template])
        [registry api-url] (resolve-registry-url)]
    (cond
      ;; Static template (built-in)
      (static-templates template)
      (let [resource (io/resource (str "compose-templates/" template ".yml"))]
        (if-not resource
          (not-found (str "Template file missing: " template))
          (let [yaml     (slurp resource)
                resolved (-> yaml
                              (str/replace "{{REGISTRY}}" registry)
                              (str/replace "{{SMITHR_URL}}" api-url))]
            {:status  200
             :headers {"Content-Type"        "application/x-yaml; charset=utf-8"
                       "Content-Disposition" (str "inline; filename=\"" template ".yml\"")}
             :body    resolved})))

      ;; Dynamic template (published via API)
      (templates/get-template template)
      (let [yaml (templates/generate-proxy-compose template registry api-url)]
        (if yaml
          {:status  200
           :headers {"Content-Type"        "application/x-yaml; charset=utf-8"
                     "Content-Disposition" (str "inline; filename=\"" template ".yml\"")}
           :body    yaml}
          (not-found (str "Failed to generate proxy for template: " template))))

      :else
      (let [all-names (sort (into static-templates (templates/template-names)))]
        (not-found (str "Unknown template: " template
                        ". Available: " (str/join ", " all-names)))))))

;; ---------------------------------------------------------------------------
;; Template publish handlers
;; ---------------------------------------------------------------------------

(defn publish-template
  "POST /api/templates — publish a dynamic compose template.
   Body: {name, compose_yaml, ports, type, platform}"
  [request]
  (let [body (:body-params request)
        tname (or (:name body) (get body "name"))
        compose (or (:compose_yaml body) (get body "compose_yaml"))
        ports (vec (or (:ports body) (get body "ports") [3000]))
        rtype (or (:type body) (get body "type") "server")
        platform (or (:platform body) (get body "platform"))]
    (cond
      (not tname)
      {:status 400 :body {:error "bad_request" :message "Missing 'name'"}}

      (not compose)
      {:status 400 :body {:error "bad_request" :message "Missing 'compose_yaml'"}}

      (not platform)
      {:status 400 :body {:error "bad_request" :message "Missing 'platform'"}}

      (static-templates tname)
      (conflict (str "Cannot overwrite built-in template: " tname))

      :else
      (let [meta {:ports ports :type rtype :platform platform}
            result (templates/save-template! tname meta compose)]
        (json-response 201 {:name tname
                            :type rtype
                            :platform platform
                            :ports ports
                            :message (str "Template '" tname "' published. "
                                          "Fetch proxy: GET /api/compose/" tname)})))))

(defn list-published-templates
  "GET /api/templates — list all published (dynamic) templates."
  [_request]
  (json-response (templates/list-templates)))

(defn delete-published-template
  "DELETE /api/templates/:name — remove a published template."
  [request]
  (let [tname (get-in request [:path-params :name])]
    (if (templates/get-template tname)
      (do (templates/delete-template! tname)
          {:status 204 :body nil})
      (not-found (str "Template not found: " tname))))
)
