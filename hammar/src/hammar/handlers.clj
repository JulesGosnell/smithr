(ns hammar.handlers
  "Ring handler implementations for the Hammar API."
  (:require [hammar.state :as state]
            [hammar.lease :as lease]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))

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
  "Convert a keyword map to underscore-string keys for JSON."
  [m]
  (into {}
        (map (fn [[k v]]
               [(-> (name k) (clojure.string/replace "-" "_") keyword)
                (if (map? v) (kw->underscore v) v)]))
        m))

(defn- serialize-resource [r]
  (-> r
      (update :type name)
      (update :platform name)
      (update :status name)
      (update :updated-at serialize-instant)
      (dissoc :parent)
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
        params    (cond-> {:type        (or (:type body) (get body "type"))
                           :platform    (or (:platform body) (get body "platform"))
                           :ttl-seconds (or (:ttl_seconds body) (get body "ttl_seconds") 1800)
                           :lessee      (or (:lessee body) (get body "lessee") "anonymous")
                           :lease-type  (keyword (or (:lease_type body) (get body "lease_type") "phone"))}
                    workspace (assoc :workspace workspace))]
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

(defn release-lease
  "DELETE /api/leases/:id"
  [request]
  (let [id (get-in request [:path-params :id])]
    (if (lease/release! id)
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
        all-connected? (every? :connected? hosts)]
    (json-response {:status     (if all-connected? "ok" "degraded")
                    :hosts      (count hosts)
                    :resources  (count resources)
                    :leases     (count leases)
                    :workspaces (count workspaces)})))

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
        (conflict (str "Workspace '" ws-name "' is currently leased — release the lease first"))
        (do
          (lease/purge-workspace! ws-name)
          {:status 204 :body nil}))
      (not-found (str "Workspace not found: " ws-name)))))

(defn serve-openapi
  "GET /openapi.yaml"
  [_request]
  (let [spec (slurp (io/resource "openapi.yaml"))]
    {:status  200
     :headers {"Content-Type" "text/yaml; charset=utf-8"}
     :body    spec}))
