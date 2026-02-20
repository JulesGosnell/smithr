(ns smithr.ui.core
  "Reagent app entry point."
  (:require [reagent.dom.client :as rdc]
            [smithr.ui.dashboard :as dashboard]
            [smithr.ui.api :as api]))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn init []
  ;; Start polling every 4 seconds
  (api/start-polling! 4000)
  ;; Mount the app
  (rdc/render root [dashboard/dashboard]))
