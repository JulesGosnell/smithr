(ns hammar.api
  "Reitit routes and Ring middleware for Hammar API.
   Routes are defined manually (matching the OpenAPI spec)
   with reitit coercion and Muuntaja content negotiation."
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.coercion :as coercion]
            [muuntaja.core :as m]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [hammar.handlers :as h]))

(def routes
  [["/api"
    ["/resources" {:get {:handler h/list-resources}}]
    ["/resources/:id" {:get {:handler h/get-resource}}]
    ["/leases"
     {:get  {:handler h/list-leases}
      :post {:handler h/acquire-lease}}]
    ["/leases/:id"
     {:get    {:handler h/get-lease}
      :delete {:handler h/unlease}}]
    ["/hosts" {:get {:handler h/list-hosts}}]
    ["/workspaces" {:get {:handler h/list-workspaces}}]
    ["/workspaces/:name" {:get    {:handler h/get-workspace}
                          :delete {:handler h/purge-workspace}}]
    ["/health" {:get {:handler h/health-check}}]]
   ["/openapi.yaml" {:get {:handler h/serve-openapi}}]])

(defn app
  "Create the Ring handler with middleware."
  []
  (ring/ring-handler
   (ring/router
    routes
    {:data {:muuntaja   m/instance
            :middleware [wrap-params
                        wrap-keyword-params
                        muuntaja/format-middleware
                        coercion/coerce-exceptions-middleware
                        coercion/coerce-request-middleware
                        coercion/coerce-response-middleware]}})
   ;; Default handler: serve static files from resources/public
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler
     {:not-found (constantly {:status 404
                              :headers {"Content-Type" "application/json"}
                              :body "{\"error\":\"not_found\"}"})}))))
