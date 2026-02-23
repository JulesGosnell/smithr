(ns smithr.ui.dashboard
  "Dashboard components for the Smithr UI — nested box visualization.
   Hosts contain resources, resources contain child resources and leases."
  (:require [reagent.core :as r]
            [smithr.ui.state :as state]
            [smithr.ui.api :as api]))

;; ---------------------------------------------------------------------------
;; Younger Futhark rune mapping (romanized → Unicode)
;; ---------------------------------------------------------------------------

(def rune-map
  {"fe"      "ᚠ"
   "ur"      "ᚢ"
   "thurs"   "ᚦ"
   "oss"     "ᚨ"
   "reid"    "ᚱ"
   "kaun"    "ᚲ"
   "hagall"  "ᚺ"
   "naud"    "ᚾ"
   "iss"     "ᛁ"
   "ar"      "ᛂ"
   "sol"     "ᛊ"
   "tyr"     "ᛏ"
   "bjarkan" "ᛒ"
   "madhr"   "ᛗ"
   "logr"    "ᛚ"
   "yr"      "ᛦ"})

(defn- runic-name
  "Replace the romanized rune suffix of a container name with its Unicode rune.
   e.g. 'smithr-android-fe' → 'smithr-android-ᚠ'"
  [container-name]
  (if-let [idx (some-> container-name (.lastIndexOf "-"))]
    (let [suffix (subs container-name (inc idx))]
      (if-let [rune (get rune-map suffix)]
        (str (subs container-name 0 (inc idx)) rune)
        container-name))
    container-name))

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

(defn- platform-icon
  "Return an icon string for a resource based on type + platform."
  [resource]
  (let [type (:type resource)
        platform (:platform resource)]
    (case [type platform]
      ["phone" "android"]         "\uD83E\uDD16"   ;; 🤖 Android robot
      ["phone" "ios"]             "\uD83D\uDCF1"   ;; 📱 Phone
      ["vm" "macos"]              "\uD83C\uDF4E"   ;; 🍎 Apple
      ["vm" "android"]            "\uD83D\uDD28"   ;; 🔨 Build hammer
      ["vm" "android-build"]      "\uD83D\uDD28"   ;; 🔨 Build hammer
      "\uD83D\uDCE6")))                             ;; 📦 Generic

(defn- status-icon
  "Return an icon for resource status."
  [status]
  (case status
    "warm"    "\u2705"      ;; ✅ Available
    "leased"  "\uD83D\uDD12"  ;; 🔒 Locked
    "shared"  "\uD83D\uDD17"  ;; 🔗 Shared
    "booting" "\u23F3"      ;; ⏳ Booting
    "dead"    "\uD83D\uDC80"  ;; 💀 Dead
    "pinned"  "\uD83D\uDCCC"  ;; 📌 Pinned
    ""))

(defn- substrate-icon
  "Return an icon for resource substrate (how it runs)."
  [substrate]
  (case substrate
    "physical"  "\uD83D\uDD0C"  ;; 🔌 Physical (plugged in)
    "emulated"  "\uD83D\uDCBB"  ;; 💻 Emulated
    "simulated" "\uD83C\uDFAD"  ;; 🎭 Simulated
    "virtual"   "\u2601"        ;; ☁ Virtual
    nil))

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
;; Sparkline + Metrics bar
;; ---------------------------------------------------------------------------

(defn- threshold-class
  "Return CSS class for a utilisation fraction (0.0-1.0)."
  [v]
  (cond
    (nil? v)   "normal"
    (> v 0.9)  "critical"
    (> v 0.7)  "warning"
    :else      "normal"))

(defn- threshold-color
  "Return CSS color var for a utilisation fraction."
  [v]
  (cond
    (nil? v)   "var(--green)"
    (> v 0.9)  "var(--red)"
    (> v 0.7)  "var(--orange)"
    :else      "var(--green)"))

(defn sparkline
  "Render an inline SVG sparkline from a seq of 0.0-1.0 values.
   Color determined by the most recent value."
  [values]
  (when (seq values)
    (let [w 80 h 20
          n (count values)
          ;; Distribute points across width
          points (map-indexed
                  (fn [i v]
                    (let [x (* (/ i (max 1 (dec n))) w)
                          y (- h (* (min 1.0 (max 0.0 v)) h))]
                      (str x "," y)))
                  values)
          color (threshold-color (last values))]
      [:svg.sparkline {:width w :height h :viewBox (str "0 0 " w " " h)}
       [:polyline {:points (clojure.string/join " " points)
                   :fill "none"
                   :stroke color
                   :stroke-width 1.5
                   :stroke-linejoin "round"
                   :stroke-linecap "round"}]])))

(defn- format-pct [v]
  (when v (str (Math/round (* v 100)) "%")))

(defn metrics-bar
  "Render a metrics bar for a resource. Shows CPU/MEM/DISK sparklines."
  [resource-id]
  (let [m (get @state/metrics resource-id)]
    (when m
      (let [cpu-vals (get m "cpu")
            mem-vals (get m "mem")
            disk-vals (get m "disk")]
        (when (or (seq cpu-vals) (seq mem-vals) (seq disk-vals))
          [:div.metrics-bar
           ;; CPU
           (when (seq cpu-vals)
             [:div.metric
              [:span.metric-label "CPU"]
              [sparkline cpu-vals]
              [:span.metric-value {:class (threshold-class (get m "cpu_current"))}
               (format-pct (get m "cpu_current"))]
              (when (get m "cpu_cores")
                [:span.metric-total (str " / " (get m "cpu_cores") " cores")])])
           ;; MEM
           (when (seq mem-vals)
             [:div.metric
              [:span.metric-label "MEM"]
              [sparkline mem-vals]
              [:span.metric-value {:class (threshold-class (get m "mem_current"))}
               (format-pct (get m "mem_current"))]
              (when (get m "mem_total_gb")
                [:span.metric-total (str " / " (get m "mem_total_gb") " GB")])])
           ;; DISK
           (when (seq disk-vals)
             [:div.metric
              [:span.metric-label "DISK"]
              [sparkline disk-vals]
              [:span.metric-value {:class (threshold-class (get m "disk_current"))}
               (format-pct (get m "disk_current"))]
              (when (get m "disk_total_gb")
                [:span.metric-total (str " / " (get m "disk_total_gb") " GB")])])])))))

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
      [:span.resource-icon (platform-icon resource)]
      [:span.box-title (runic-name (:container resource))]
      [:span.box-meta
       (:type resource) " · " (:platform resource)
       (when-let [sub (:substrate resource)]
         (str " · " sub))
       (when-let [model (:model resource)]
         (str " · " model))
       (when max-slots (str " · " active-count "/" max-slots " slots"))]
      [:span.box-status {:class status}
       (str (when-let [si (substrate-icon (:substrate resource))]
              (str si " "))
            (status-icon status) " " status)]
      (when remaining
        [:span.countdown {:class urgency} (str "\u23F1 " remaining)])]

     ;; Resource metrics sparklines
     [metrics-bar (:id resource)]

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
      [:span.resource-icon "\uD83D\uDDA5"]  ;; 🖥 Server
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
;; Adopt box (adopted external containers)
;; ---------------------------------------------------------------------------

(defn adopt-box [adopt]
  (let [_ @tick
        remaining (time-remaining (:expires_at adopt))
        urgency (countdown-urgency (:expires_at adopt) (:ttl_seconds adopt))
        ports (:ports adopt)]
    [:div.nested-box.adopt-box {:class urgency}
     [:div.box-header
      [:span.resource-icon "\uD83D\uDD17"]  ;; 🔗 Link
      [:span.box-title (:container_name adopt)]
      [:span.box-meta
       (:lessee adopt) " · " (:host adopt)]
      (when remaining
        [:span.countdown {:class urgency} (str "\u23F1 " remaining)])]
     [:div.adopt-ports
      (for [[orig tunnel] (sort-by first ports)]
        ^{:key orig}
        [:span.port-mapping (str orig " \u2192 " tunnel)])]
     [:div.box-controls
      [:button.btn.release {:on-click #(api/unadopt! (:id adopt))} "Unadopt"]]]))

(defn adopts-section []
  (let [adopts @state/adopts]
    (when (seq adopts)
      [:div.adopts-section
       [:div.section-header "Adopted Containers"]
       [:div.adopts-grid
        (for [a (sort-by :container_name adopts)]
          ^{:key (:id a)} [adopt-box a])]])))

;; ---------------------------------------------------------------------------
;; Catalogue section — provisionable resource templates
;; ---------------------------------------------------------------------------

(defn- template-status
  "Determine the live status of a catalogue template by checking active resources."
  [template active-resources]
  (let [matches (->> active-resources
                     (filter #(and (= (:type %) (:type template))
                                   (= (:platform %) (:platform template)))))]
    (cond
      (some #(#{"warm" "shared"} (:status %)) matches) :up
      (some #(= (:status %) "booting") matches)        :booting
      (some #(= (:status %) "leased") matches)         :busy
      :else                                             :down)))

(defn- status-badge [status]
  (case status
    :up      [:span.catalogue-badge.up "\u2705 up"]
    :booting [:span.catalogue-badge.booting "\u23F3 booting"]
    :busy    [:span.catalogue-badge.busy "\uD83D\uDD12 busy"]
    :down    [:span.catalogue-badge.down "\u26AA down"]))

(defn- provision-template! [template]
  (api/acquire-lease! (:type template) (:platform template) "phone"))

(defn catalogue-card [template active-resources]
  (let [status (template-status template active-resources)
        running-count (->> active-resources
                           (filter #(and (= (:type %) (:type template))
                                         (= (:platform %) (:platform template))
                                         (#{"warm" "shared" "leased" "booting"} (:status %))))
                           count)]
    [:div.nested-box.catalogue-card {:class (name status)}
     [:div.box-header
      [:span.resource-icon
       (case [(:type template) (:platform template)]
         ["phone" "android"]    "\uD83E\uDD16"
         ["phone" "ios"]        "\uD83D\uDCF1"
         ["vm" "macos"]         "\uD83C\uDF4E"
         ["vm" "android-build"] "\uD83D\uDD28"
         "\uD83D\uDCE6")]
      [:span.box-title (:key template)]
      [status-badge status]]
     [:div.catalogue-meta
      [:span (:type template) " \u00B7 " (:platform template)]
      (when (:substrate template)
        [:span " \u00B7 " (:substrate template)])
      (when (:model template)
        [:span " \u00B7 " (:model template)])
      (when (:os template)
        [:span " \u00B7 " (:os template)])]
     [:div.catalogue-desc (:description template)]
     [:div.catalogue-footer
      [:span.catalogue-count (str running-count " running")]
      [:span.catalogue-boot
       (str "\u23F1 ~" (:boot_time_seconds template 60) "s boot")]
      (when (= status :down)
        [:button.btn.lease {:on-click #(provision-template! template)} "Provision"])]]))

(defn catalogue-section []
  (let [cat @state/catalogue]
    (when (and cat (seq (:templates cat)))
      [:div.catalogue-section {:id "catalogue"}
       [:div.section-header "Resource Catalogue"]
       [:div.catalogue-grid
        (for [t (:templates cat)]
          ^{:key (:key t)} [catalogue-card t (or (:active_resources cat) [])])]])))

;; ---------------------------------------------------------------------------
;; Top-level components
;; ---------------------------------------------------------------------------

(defn header []
  (let [h @state/health]
    [:div.header
     [:h1 "SMITHR"
      (when-let [hash (or (:git-hash h) (:git_hash h))]
        [:span.git-hash (str " (" hash ")")])]
     [:div.header-right
      [:a.header-link {:href "#catalogue"} "Catalogue"]
      [:a.header-link {:href "/swagger" :target "_blank"} "API"]
      [:span.status {:class (if (= (:status h) "ok") "connected" "disconnected")}
       (if h
         (str (:status h) " \u2502 " (:hosts h) " hosts \u2502 "
              (:resources h) " resources \u2502 " (:leases h) " leases"
              (when (pos? (:workspaces h 0))
                (str " \u2502 " (:workspaces h) " workspaces"))
              (when (pos? (:adopts h 0))
                (str " \u2502 " (:adopts h) " adopts")))
         "connecting...")]]]))

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
        container (runic-name (or (:container event) (:resource event) "?"))
        lease-type (or (:lease-type event) "")
        ttl (:ttl event)
        held (:held-seconds event)
        ws (:workspace event)]
    (case t
      "lease"   (str lessee " leases " container
                     (when (seq lease-type) (str " (" lease-type ")"))
                     (when ws (str " workspace:" ws))
                     (when ttl (str " for " (format-duration ttl))))
      "unlease" (str lessee " unleases " container
                     (when ws (str " workspace:" ws))
                     (when held (str " held for " (format-duration held))))
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
     [catalogue-section]
     [adopts-section]
     [audit-pane]]))
