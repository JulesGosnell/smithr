(ns smithr.handlers
  "Ring handler implementations for the Smithr API."
  (:require [smithr.state :as state]
            [smithr.lease :as lease]
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
        true           (dissoc :active-leases))
      kw->underscore))

(defn- serialize-lease [l]
  (-> l
      (update :acquired-at serialize-instant)
      (update :expires-at serialize-instant)
      (cond->
        (:lease-type l) (update :lease-type name))
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
        prefer-host (or (:prefer_host body) (get body "prefer_host"))
        reverse-ports (or (:reverse_ports body) (get body "reverse_ports"))
        params    (cond-> {:type        (or (:type body) (get body "type"))
                           :platform    (or (:platform body) (get body "platform"))
                           :ttl-seconds (or (:ttl_seconds body) (get body "ttl_seconds") 1800)
                           :lessee      (or (:lessee body) (get body "lessee") "anonymous")
                           :lease-type  (keyword (or (:lease_type body) (get body "lease_type") "phone"))}
                    workspace (assoc :workspace workspace)
                    prefer-host (assoc :prefer-host prefer-host)
                    (seq server-ports) (assoc :server-ports (vec server-ports))
                    (seq reverse-ports) (assoc :reverse-ports (vec reverse-ports)))]
    (log/info "Lease request:" params)
    (try
      (if-let [result (lease/acquire! params)]
        (json-response 201 (serialize-lease result))
        (conflict (str "No available " (:type params) ":" (:platform params) " resource")))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (:lease-id data)
            ;; Workspace already leased
            (conflict (str "Workspace '" (:workspace data) "' is already leased"))
            ;; Invalid workspace name
            {:status 400
             :body   {:error "bad_request"
                      :message (.getMessage e)}}))))))

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
                    :git-hash   git-hash})))

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
        params {:container-name (or (:container_name body) (get body "container_name"))
                :ports          (vec (or (:ports body) (get body "ports")))
                :lessee         (or (:lessee body) (get body "lessee") "anonymous")
                :ttl-seconds    (or (:ttl_seconds body) (get body "ttl_seconds") 3600)}]
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
    (if (lease/unadopt! id)
      {:status 204 :body nil}
      (not-found (str "Adopt not found: " id)))))

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

(def ^:private valid-templates
  #{"android-phone" "ios-phone" "macos-build" "android-build"})

(defn serve-compose-template
  "GET /api/compose/:template — serve a ready-to-use compose YAML for a resource type.
   Substitutes {{REGISTRY}} and {{SMITHR_URL}} with the current host's values."
  [request]
  (let [template (get-in request [:path-params :template])]
    (if-not (valid-templates template)
      (not-found (str "Unknown template: " template
                      ". Available: " (str/join ", " (sort valid-templates))))
      (let [resource (io/resource (str "compose-templates/" template ".yml"))]
        (if-not resource
          (not-found (str "Template file missing: " template))
          (let [yaml     (slurp resource)
                ;; Resolve placeholders with runtime values
                registry (or (System/getenv "SMITHR_REGISTRY") "localhost:5000")
                api-url  (or (System/getenv "SMITHR_URL") "http://10.21.0.1:7070")
                resolved (-> yaml
                              (str/replace "{{REGISTRY}}" registry)
                              (str/replace "{{SMITHR_URL}}" api-url))]
            {:status  200
             :headers {"Content-Type"        "application/x-yaml; charset=utf-8"
                       "Content-Disposition" (str "inline; filename=\"" template ".yml\"")}
             :body    resolved}))))))
