(ns smithr.ui.state
  "Client-side state management using Reagent atoms."
  (:require [reagent.core :as r]))

(defonce resources (r/atom []))
(defonce leases (r/atom []))
(defonce hosts (r/atom []))
(defonce workspaces (r/atom []))
(defonce adopts (r/atom []))
(defonce health (r/atom nil))
(defonce events (r/atom []))
(defonce metrics (r/atom {}))
(defonce catalogue (r/atom nil))
(defonce error (r/atom nil))
(defonce last-updated (r/atom nil))
