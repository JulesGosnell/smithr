(ns smithr.ui.api
  "HTTP client for polling the Smithr API."
  (:require [ajax.core :refer [GET POST DELETE]]
            [smithr.ui.state :as state]))

(def base-url "")  ;; same origin

(defn- handle-error [prefix {:keys [status status-text]}]
  (reset! state/error (str prefix ": " status " " status-text)))

(defn fetch-resources! []
  (GET (str base-url "/api/resources")
    {:handler         #(do (reset! state/resources %)
                           (reset! state/last-updated (js/Date.))
                           (reset! state/error nil))
     :error-handler   (partial handle-error "Resources")
     :response-format :json
     :keywords?       true}))

(defn fetch-leases! []
  (GET (str base-url "/api/leases")
    {:handler         #(reset! state/leases %)
     :error-handler   (partial handle-error "Leases")
     :response-format :json
     :keywords?       true}))

(defn fetch-hosts! []
  (GET (str base-url "/api/hosts")
    {:handler         #(reset! state/hosts %)
     :error-handler   (partial handle-error "Hosts")
     :response-format :json
     :keywords?       true}))

(defn fetch-health! []
  (GET (str base-url "/api/health")
    {:handler         #(reset! state/health %)
     :error-handler   (partial handle-error "Health")
     :response-format :json
     :keywords?       true}))

(defn fetch-workspaces! []
  (GET (str base-url "/api/workspaces")
    {:handler         #(reset! state/workspaces %)
     :error-handler   (partial handle-error "Workspaces")
     :response-format :json
     :keywords?       true}))

(defn fetch-adopts! []
  (GET (str base-url "/api/adopts")
    {:handler         #(reset! state/adopts %)
     :error-handler   (partial handle-error "Adopts")
     :response-format :json
     :keywords?       true}))

(defn fetch-events! []
  (GET (str base-url "/api/events?limit=50")
    {:handler         #(reset! state/events %)
     :error-handler   (partial handle-error "Events")
     :response-format :json
     :keywords?       true}))

(defn fetch-metrics! []
  (GET (str base-url "/api/metrics")
    {:handler         #(reset! state/metrics %)
     :error-handler   (partial handle-error "Metrics")
     :response-format :json
     :keywords?       false}))

(defn fetch-catalogue! []
  (GET (str base-url "/api/catalogue")
    {:handler         #(reset! state/catalogue %)
     :error-handler   (partial handle-error "Catalogue")
     :response-format :json
     :keywords?       true}))

(defn fetch-all! []
  (fetch-resources!)
  (fetch-leases!)
  (fetch-hosts!)
  (fetch-workspaces!)
  (fetch-adopts!)
  (fetch-events!)
  (fetch-health!)
  (fetch-metrics!)
  (fetch-catalogue!))

(defn acquire-lease!
  ([resource-type platform]
   (acquire-lease! resource-type platform "phone"))
  ([resource-type platform lease-type]
   (POST (str base-url "/api/leases")
     {:params          {:type resource-type
                        :platform platform
                        :lease_type lease-type
                        :ttl_seconds 1800
                        :lessee "dashboard"}
      :handler         (fn [_] (fetch-all!))
      :error-handler   (partial handle-error "Acquire")
      :format          :json
      :response-format :json
      :keywords?       true})))

(defn acquire-workspace-lease!
  [resource-type platform workspace-name]
  (POST (str base-url "/api/leases")
    {:params          {:type resource-type
                       :platform platform
                       :lease_type "build"
                       :workspace workspace-name
                       :ttl_seconds 1800
                       :lessee "dashboard"}
     :handler         (fn [_] (fetch-all!))
     :error-handler   (partial handle-error "Acquire workspace")
     :format          :json
     :response-format :json
     :keywords?       true}))

(defn unlease! [lease-id]
  (DELETE (str base-url "/api/leases/" lease-id)
    {:handler       (fn [_] (fetch-all!))
     :error-handler (partial handle-error "Unlease")}))

(defn unadopt! [adopt-id]
  (DELETE (str base-url "/api/adopts/" adopt-id)
    {:handler       (fn [_] (fetch-all!))
     :error-handler (partial handle-error "Unadopt")}))

(defn purge-workspace! [workspace-name]
  (DELETE (str base-url "/api/workspaces/" workspace-name)
    {:handler       (fn [_] (fetch-all!))
     :error-handler (partial handle-error "Purge workspace")}))

(defn provision! [template-key]
  (POST (str base-url "/api/provision")
    {:params          {:template template-key}
     :handler         (fn [_] (fetch-all!))
     :error-handler   (partial handle-error "Provision")
     :format          :json
     :response-format :json
     :keywords?       true}))

(defn start-polling!
  "Start polling the API every interval-ms."
  [interval-ms]
  (fetch-all!)
  (js/setInterval fetch-all! interval-ms))
