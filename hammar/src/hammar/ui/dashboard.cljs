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

(defn- leases-for-resource
  "Find all leases for a given resource (for shared VMs with multiple build leases)."
  [resource-id]
  (filter #(= (:resource_id %) resource-id) @state/leases))

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
        booting (count (filter #(= (:status %) "booting") resources))
        shared (count (filter #(= (:status %) "shared") resources))]
    [:div.summary
     [:div.stat [:span.value total] [:span.label "total"]]
     [:div.stat [:span.value {:style {:color "var(--green)"}} warm] [:span.label "available"]]
     [:div.stat [:span.value {:style {:color "var(--red)"}} leased] [:span.label "leased"]]
     [:div.stat [:span.value {:style {:color "var(--yellow)"}} booting] [:span.label "booting"]]
     [:div.stat [:span.value {:style {:color "var(--blue, #4a9eff)"}} shared] [:span.label "shared"]]]))

(defn resource-card [resource]
  (let [status (:status resource)
        lease (when (= status "leased") (lease-for-resource (:id resource)))
        active-leases (when (= status "shared") (leases-for-resource (:id resource)))
        connection (:connection resource)
        vnc-port (:vnc_port connection)
        macos-vm? (= (:platform resource) "macos")
        max-slots (:max_slots resource)
        active-count (:active_lease_count resource 0)]
    [:div.resource-card {:class status}
     [:div.resource-id (:id resource)]
     [:div.resource-meta
      [:span.badge (:type resource)]
      [:span.badge (:platform resource)]
      [:span.badge (:container resource)]
      ;; Show slot usage for macOS VMs
      (when max-slots
        [:span.badge {:style {:background "var(--blue, #4a9eff)"
                              :color "white"}}
         (str active-count "/" max-slots " builds")])]
     ;; Show single lease info for phone leases
     (when lease
       [:div.lease-info
        [:span.lessee (:lessee lease)]
        [:span " | "]
        [:span.ttl (time-remaining (:expires_at lease))]])
     ;; Show multiple build lease info for shared VMs
     (when (seq active-leases)
       [:div.lease-info
        (for [l active-leases]
          ^{:key (:id l)}
          [:div {:style {:font-size "0.8rem" :margin-top "0.25rem"}}
           [:span.lessee (:lessee l)]
           (when (:macos_user l) [:span " (" (:macos_user l) ")"])
           [:span " | "]
           [:span.ttl (time-remaining (:expires_at l))]])])
     [:div {:style {:margin-top "0.5rem" :display "flex" :gap "0.5rem" :flex-wrap "wrap"}}
      (case status
        "warm" (if macos-vm?
                 ;; macOS VMs get two lease buttons
                 (list
                  ^{:key "build"} [:button.btn.lease
                                   {:on-click #(api/acquire-lease! (:type resource) (:platform resource) "build")}
                                   "Build Lease"]
                  ^{:key "phone"} [:button.btn.lease
                                   {:on-click #(api/acquire-lease! (:type resource) (:platform resource) "phone")
                                    :style {:background "var(--orange, #f59e0b)"}}
                                   "Phone Lease"])
                 ;; Non-macOS: single lease button
                 [:button.btn.lease
                  {:on-click #(api/acquire-lease! (:type resource) (:platform resource))}
                  "Lease"])
        "shared" (list
                  ;; Can add more build leases to shared VMs
                  ^{:key "build"} [:button.btn.lease
                                   {:on-click #(api/acquire-lease! (:type resource) (:platform resource) "build")}
                                   "Add Build"]
                  ;; Release buttons for each active lease
                  (for [l active-leases]
                    ^{:key (str "rel-" (:id l))}
                    [:button.btn.release
                     {:on-click #(api/release-lease! (:id l))
                      :style {:font-size "0.75rem"}}
                     (str "Release " (or (:macos_user l) (subs (:id l) 0 8)))]))
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
