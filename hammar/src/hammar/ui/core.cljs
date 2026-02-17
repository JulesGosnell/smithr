(ns hammar.ui.core
  "Reagent app entry point."
  (:require [reagent.dom.client :as rdc]
            [hammar.ui.dashboard :as dashboard]
            [hammar.ui.api :as api]))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn init []
  ;; Start polling every 4 seconds
  (api/start-polling! 4000)
  ;; Mount the app
  (rdc/render root [dashboard/dashboard]))
