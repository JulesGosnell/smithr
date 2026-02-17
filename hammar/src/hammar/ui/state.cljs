(ns hammar.ui.state
  "Client-side state management using Reagent atoms."
  (:require [reagent.core :as r]))

(defonce resources (r/atom []))
(defonce leases (r/atom []))
(defonce hosts (r/atom []))
(defonce health (r/atom nil))
(defonce error (r/atom nil))
(defonce last-updated (r/atom nil))
