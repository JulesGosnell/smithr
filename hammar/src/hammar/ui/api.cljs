(ns hammar.ui.api
  "HTTP client for polling the Hammar API."
  (:require [ajax.core :refer [GET POST DELETE]]
            [hammar.ui.state :as state]))

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

(defn fetch-all! []
  (fetch-resources!)
  (fetch-leases!)
  (fetch-hosts!)
  (fetch-health!))

(defn acquire-lease! [resource-type platform]
  (POST (str base-url "/api/leases")
    {:params          {:type resource-type
                       :platform platform
                       :ttl_seconds 1800
                       :lessee "dashboard"}
     :handler         (fn [_] (fetch-all!))
     :error-handler   (partial handle-error "Acquire")
     :format          :json
     :response-format :json
     :keywords?       true}))

(defn release-lease! [lease-id]
  (DELETE (str base-url "/api/leases/" lease-id)
    {:handler       (fn [_] (fetch-all!))
     :error-handler (partial handle-error "Release")}))

(defn start-polling!
  "Start polling the API every interval-ms."
  [interval-ms]
  (fetch-all!)
  (js/setInterval fetch-all! interval-ms))
