(ns hammar.ui.dashboard
  "Dashboard components for the Hammar UI — nested box visualization.
   Hosts contain resources, resources contain child resources and leases."
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

(defn- countdown-urgency
  "Return urgency class based on % of TTL remaining.
   Final 20% is urgent (red pulsing)."
  [expires-at ttl-seconds]
  (when (and expires-at ttl-seconds (pos? ttl-seconds))
    (let [exp (.getTime (js/Date. expires-at))
          now (.getTime (js/Date.))
          remaining (/ (- exp now) 1000)
          pct (/ remaining ttl-seconds)]
      (cond
        (<= pct 0)   "expired"
        (<= pct 0.2) "urgent"
        (<= pct 0.5) "warning"
        :else         "ok"))))

(defn- leases-for-resource
  "Find all leases for a given resource."
  [resource-id]
  (filter #(= (:resource_id %) resource-id) @state/leases))

(defn- children-of
  "Find child resources whose :parent matches container name."
  [container-name all-resources]
  (filter #(= (:parent %) container-name) all-resources))

(defn- root-resources
  "Resources that have no parent (top-level within a host)."
  [all-resources]
  (filter #(nil? (:parent %)) all-resources))

;; ---------------------------------------------------------------------------
;; Tick timer — forces re-render every second for live countdowns
;; ---------------------------------------------------------------------------

(defonce tick (r/atom 0))
(defonce tick-interval
  (js/setInterval #(swap! tick inc) 1000))

;; ---------------------------------------------------------------------------
;; Lease box (innermost nesting level)
;; ---------------------------------------------------------------------------

(defn lease-box [lease]
  (let [_ @tick  ;; subscribe to tick for live countdown
        remaining (time-remaining (:expires_at lease))
        urgency (countdown-urgency (:expires_at lease) (:ttl_seconds lease))]
    [:div.nested-box.lease-box {:class urgency}
     [:div.box-header
      [:span.box-title
       (or (:macos_user lease) (subs (:id lease) 0 8))]
      [:span.box-meta (:lessee lease)]
      (when remaining
        [:span.countdown {:class urgency} (str "\u23F1 " remaining)])]
     [:div.box-controls
      [:button.btn.release {:on-click #(api/unlease! (:id lease))} "Unlease"]]]))

;; ---------------------------------------------------------------------------
;; Workspace box (shown inside VM resource)
;; ---------------------------------------------------------------------------

(defn workspace-box [ws]
  (let [_ @tick
        leased? (= (:status ws) "leased")
        lease (when leased?
                (some #(when (= (:id %) (:lease_id ws)) %) @state/leases))
        remaining (when lease (time-remaining (:expires_at lease)))
        urgency (when lease (countdown-urgency (:expires_at lease) (:ttl_seconds lease)))]
    [:div.nested-box.workspace-box {:class (if leased? "leased" "idle")}
     [:div.box-header
      [:span.box-title (str "\uD83D\uDCC1 " (:name ws))]
      [:span.box-meta (if leased? (str (:lessee lease)) "idle")]
      (when remaining
        [:span.countdown {:class urgency} (str "\u23F1 " remaining)])]
     [:div.box-controls
      (if leased?
        [:button.btn.release {:on-click #(api/unlease! (:id lease))} "Unlease"]
        [:button.btn.purge {:on-click #(api/purge-workspace! (:name ws))} "Purge"])]]))

;; ---------------------------------------------------------------------------
;; Resource box (contains child resources + leases)
;; ---------------------------------------------------------------------------

(defn resource-box [resource all-resources]
  (let [_ @tick
        status (:status resource)
        macos-vm? (= (:platform resource) "macos")
        max-slots (:max_slots resource)
        active-count (:active_lease_count resource 0)
        children (children-of (:container resource) all-resources)
        leases (leases-for-resource (:id resource))
        connection (:connection resource)
        vnc-port (:vnc_port connection)
        ;; Find workspaces for this resource
        ws-list (filter #(= (:resource_id %) (:id resource)) @state/workspaces)
        ;; For exclusive phone lease
        phone-lease (when (= status "leased")
                      (first leases))
        remaining (when phone-lease (time-remaining (:expires_at phone-lease)))
        urgency (when phone-lease (countdown-urgency (:expires_at phone-lease) (:ttl_seconds phone-lease)))]
    [:div.nested-box.resource-box {:class status
                                   :data-resource-id (:id resource)
                                   :data-status status
                                   :data-platform (:platform resource)}
     [:div.box-header
      [:span.box-title (:container resource)]
      [:span.box-meta
       (:type resource) " · " (:platform resource)
       (when max-slots (str " · " active-count "/" max-slots " slots"))]
      [:span.box-status {:class status} status]
      (when remaining
        [:span.countdown {:class urgency} (str "\u23F1 " remaining)])]

     ;; Phone lease info (exclusive)
     (when phone-lease
       [:div.box-lease-info
        [:span.lessee (:lessee phone-lease)]])

     ;; Nested children: child resources (e.g., iOS sim inside macOS VM)
     (when (seq children)
       [:div.nested-children
        (for [child (sort-by :id children)]
          ^{:key (:id child)} [resource-box child all-resources])])

     ;; Nested children: build leases (shared VM)
     (when (and (= status "shared") (seq leases))
       [:div.nested-children
        (for [l (sort-by :id leases)]
          ^{:key (:id l)} [lease-box l])])

     ;; Nested children: workspaces
     (when (seq ws-list)
       [:div.nested-children
        (for [ws (sort-by :name ws-list)]
          ^{:key (:name ws)} [workspace-box ws])])

     ;; Controls
     [:div.box-controls
      (case status
        "warm" (list
                (when macos-vm?
                  ^{:key "build"} [:button.btn.lease
                                   {:on-click #(api/acquire-lease! (:type resource) (:platform resource) "build")}
                                   "Build Lease"])
                ^{:key "phone"} [:button.btn.lease
                                 {:on-click #(api/acquire-lease! (:type resource) (:platform resource) "phone")
                                  :style (when macos-vm? {:background "var(--orange-bg)"
                                                          :border-color "var(--orange)"
                                                          :color "var(--orange)"})}
                                 "Phone Lease"])
        "shared" (list
                  ^{:key "build"} [:button.btn.lease
                                   {:on-click #(api/acquire-lease! (:type resource) (:platform resource) "build")}
                                   "Add Build"])
        "leased" (when phone-lease
                   [:button.btn.release
                    {:on-click #(api/unlease! (:id phone-lease))} "Unlease"])
        nil)
      (when vnc-port
        [:a.btn.vnc {:href (str "vnc://localhost:" vnc-port) :target "_blank"
                     :style {:text-decoration "none"}} "VNC"])]]))

;; ---------------------------------------------------------------------------
;; Host box (outermost level)
;; ---------------------------------------------------------------------------

(defn host-box [host-label all-resources]
  (let [host-info (some #(when (= (:label %) host-label) %) @state/hosts)
        host-resources (filter #(= (:host %) host-label) all-resources)
        roots (root-resources host-resources)
        connected? (:connected host-info true)]
    [:div.nested-box.host-box {:class (if connected? "connected" "disconnected")
                               :data-host host-label}
     [:div.box-header
      [:span.box-title host-label]
      [:span.box-meta (str (count host-resources) " resource"
                           (when (not= 1 (count host-resources)) "s"))]
      [:span.box-status {:class (if connected? "connected" "disconnected")}
       (if connected? "connected" "disconnected")]]
     (if (seq roots)
       [:div.nested-children
        (for [r (sort-by :id roots)]
          ^{:key (:id r)} [resource-box r all-resources])]
       [:div.empty "No resources on this host"])]))

;; ---------------------------------------------------------------------------
;; Top-level components
;; ---------------------------------------------------------------------------

(defn header []
  (let [h @state/health]
    [:div.header
     [:h1 "SMITHR"]
     [:span.status {:class (if (= (:status h) "ok") "connected" "disconnected")}
      (if h
        (str (:status h) " \u2502 " (:hosts h) " hosts \u2502 "
             (:resources h) " resources \u2502 " (:leases h) " leases"
             (when (pos? (:workspaces h 0))
               (str " \u2502 " (:workspaces h) " workspaces")))
        "connecting...")]]))

(defn summary-bar []
  (let [_ @tick
        resources @state/resources
        total (count resources)
        warm (count (filter #(= (:status %) "warm") resources))
        leased (count (filter #(= (:status %) "leased") resources))
        booting (count (filter #(= (:status %) "booting") resources))
        shared (count (filter #(= (:status %) "shared") resources))]
    [:div.summary
     [:div.stat [:span.value total] [:span.label "total"]]
     [:div.stat [:span.value {:style {:color "var(--green)"}} warm] [:span.label "available"]]
     [:div.stat [:span.value {:style {:color "var(--orange)"}} leased] [:span.label "leased"]]
     [:div.stat [:span.value {:style {:color "var(--yellow)"}} booting] [:span.label "booting"]]
     [:div.stat [:span.value {:style {:color "var(--blue)"}} shared] [:span.label "shared"]]]))

(defn error-banner []
  (when-let [err @state/error]
    [:div.error-banner err]))

;; ---------------------------------------------------------------------------
;; Audit pane — event history
;; ---------------------------------------------------------------------------

(defn- format-time
  "Format ISO timestamp to HH:MM:SS."
  [ts]
  (when ts
    (let [d (js/Date. ts)]
      (str (.padStart (str (.getHours d)) 2 "0") ":"
           (.padStart (str (.getMinutes d)) 2 "0") ":"
           (.padStart (str (.getSeconds d)) 2 "0")))))

(defn- format-duration [seconds]
  (when (and seconds (pos? seconds))
    (cond
      (>= seconds 3600) (str (int (/ seconds 3600)) "h " (int (/ (mod seconds 3600) 60)) "m")
      (>= seconds 60)   (str (int (/ seconds 60)) "m " (int (mod seconds 60)) "s")
      :else              (str (int seconds) "s"))))

(defn- event-description [event]
  (let [t (:type event)
        lessee (or (:lessee event) "?")
        container (or (:container event) (:resource event) "?")
        lease-type (or (:lease_type event) "")
        ttl (:ttl event)
        held (:held_seconds event)
        ws (:workspace event)]
    (case t
      "lease"   (str lessee " leases " container
                     (when (seq lease-type) (str " (" lease-type ")"))
                     (when ws (str " workspace:" ws))
                     (when ttl (str " for " (format-duration ttl))))
      "unlease" (str lessee " unleases " container
                     (when ws (str " workspace:" ws))
                     (when held (str " held " (format-duration held))))
      "gc"      (str "GC expires " container " (" lessee ")")
      (str t " " container))))

(defn- event-icon [event-type]
  (case event-type
    "lease"   "\uD83D\uDD12"   ;; 🔒
    "unlease" "\uD83D\uDD11"   ;; 🔑
    "gc"      "\uD83D\uDDD1"   ;; 🗑
    "\u2022"))                   ;; •

(defn audit-pane []
  (let [events (reverse @state/events)]
    [:div.audit-pane
     [:div.audit-header "History"]
     [:div.audit-log
      (if (seq events)
        (for [[i event] (map-indexed vector events)]
          ^{:key i}
          [:div.audit-entry {:class (:type event)}
           [:span.audit-time (format-time (:timestamp event))]
           [:span.audit-icon (event-icon (:type event))]
           [:span.audit-desc (event-description event)]])
        [:div.audit-empty "No events yet"])]]))

(defn dashboard []
  (let [all-resources @state/resources
        host-labels (->> all-resources (map :host) distinct sort)
        all-hosts (->> @state/hosts (map :label) (concat host-labels) distinct sort)]
    [:div
     [header]
     [error-banner]
     [summary-bar]
     [:div.hosts
      (if (seq all-hosts)
        (for [h all-hosts]
          ^{:key h} [host-box h all-resources])
        [:div.empty "No hosts connected. Waiting for Docker events..."])]
     [audit-pane]]))
