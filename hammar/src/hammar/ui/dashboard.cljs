(ns hammar.ui.dashboard
  "Dashboard components for the Hammar UI."
  (:require [reagent.core :as r]
            [hammar.ui.state :as state]
            [hammar.ui.api :as api]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- time-remaining
  "Compute human-readable time remaining from an ISO timestamp string."
  [expires-at]
  (when expires-at
    (let [exp (.getTime (js/Date. expires-at))
          now (.getTime (js/Date.))
          delta (/ (- exp now) 1000)]
      (cond
        (<= delta 0) "expired"
        (< delta 60) (str (int delta) "s")
        (< delta 3600) (str (int (/ delta 60)) "m " (int (mod delta 60)) "s")
        :else (str (int (/ delta 3600)) "h " (int (/ (mod delta 3600) 60)) "m")))))

(defn- lease-for-resource
  "Find the lease for a given resource, if any."
  [resource-id]
  (some #(when (= (:resource_id %) resource-id) %) @state/leases))

;; ---------------------------------------------------------------------------
;; Components
;; ---------------------------------------------------------------------------

(defn header []
  (let [h @state/health]
    [:div.header
     [:h1 "SMITHR"]
     [:span.status {:class (if (= (:status h) "ok") "connected" "disconnected")}
      (if h
        (str (:status h) " | " (:hosts h) " hosts | "
             (:resources h) " resources | " (:leases h) " leases")
        "connecting...")]]))

(defn summary-bar []
  (let [resources @state/resources
        total (count resources)
        warm (count (filter #(= (:status %) "warm") resources))
        leased (count (filter #(= (:status %) "leased") resources))
        booting (count (filter #(= (:status %) "booting") resources))]
    [:div.summary
     [:div.stat [:span.value total] [:span.label "total"]]
     [:div.stat [:span.value {:style {:color "var(--green)"}} warm] [:span.label "available"]]
     [:div.stat [:span.value {:style {:color "var(--red)"}} leased] [:span.label "leased"]]
     [:div.stat [:span.value {:style {:color "var(--yellow)"}} booting] [:span.label "booting"]]]))

(defn resource-card [resource]
  (let [status (:status resource)
        lease (when (= status "leased") (lease-for-resource (:id resource)))
        connection (:connection resource)

        vnc-port (:vnc_port connection)]
    [:div.resource-card {:class status}
     [:div.resource-id (:id resource)]
     [:div.resource-meta
      [:span.badge (:type resource)]
      [:span.badge (:platform resource)]
      [:span.badge (:container resource)]]
     (when lease
       [:div.lease-info
        [:span.lessee (:lessee lease)]
        [:span " | "]
        [:span.ttl (time-remaining (:expires_at lease))]])
     [:div {:style {:margin-top "0.5rem" :display "flex" :gap "0.5rem"}}
      (case status
        "warm" [:button.btn.lease
                {:on-click #(api/acquire-lease! (:type resource) (:platform resource))}
                "Lease"]
        "leased" (when lease
                   [:button.btn.release
                    {:on-click #(api/release-lease! (:id lease))}
                    "Release"])
        nil)
      (when vnc-port
        [:a.btn.vnc {:href (str "vnc://localhost:" vnc-port) :target "_blank"
                     :style {:text-decoration "none"}} "VNC"])]]))

(defn host-panel [host-label]
  (let [host-info (some #(when (= (:label %) host-label) %) @state/hosts)
        resources (filter #(= (:host %) host-label) @state/resources)
        connected? (:connected host-info true)]
    [:div.host-panel
     [:div.host-header
      [:h2 host-label]
      [:span.host-status {:class (if connected? "connected" "disconnected")}
       (if connected? "connected" "disconnected")]]
     (if (seq resources)
       [:div.resources
        (for [r (sort-by :id resources)]
          ^{:key (:id r)} [resource-card r])]
       [:div.empty "No resources on this host"])]))

(defn error-banner []
  (when-let [err @state/error]
    [:div {:style {:background "var(--red-bg)"
                   :color "var(--red)"
                   :padding "0.5rem 1rem"
                   :text-align "center"
                   :font-size "0.85rem"}}
     err]))

(defn dashboard []
  (let [host-labels (->> @state/resources
                         (map :host)
                         distinct
                         sort)
        ;; Also include hosts that have no resources
        all-hosts (->> @state/hosts
                       (map :label)
                       (concat host-labels)
                       distinct
                       sort)]
    [:div
     [header]
     [error-banner]
     [summary-bar]
     [:div.hosts
      (if (seq all-hosts)
        (for [h all-hosts]
          ^{:key h} [host-panel h])
        [:div.empty "No hosts connected. Waiting for Docker events..."])]]))
