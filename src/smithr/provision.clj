(ns smithr.provision
  "Lazy resource provisioning from compose layer templates.
   When a lease request arrives and no matching warm resource exists,
   Smithr can auto-provision the right container from its compose layer
   catalogue, wait for it to become healthy, then satisfy the lease.

   Entirely opt-in: requires a :provisioning section in config.
   Pre-started containers (smithr.provisioned=false) are never touched."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [smithr.state :as state]
            [smithr.config :as config])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Config access
;; ---------------------------------------------------------------------------

(defonce ^:private provisioning-config (atom nil))

(defn set-config!
  "Store the provisioning config (called from core on startup)."
  [config]
  (reset! provisioning-config (:provisioning config)))

(defn- prov-config []
  @provisioning-config)

;; ---------------------------------------------------------------------------
;; Template lookup
;; ---------------------------------------------------------------------------

(defn- type-platform->template-key
  "Map a resource type+platform to a provisioning template key.
   e.g. {:type :phone :platform :android} -> :android-phone"
  [type platform]
  (when-let [mapping (:type-mapping (prov-config))]
    (get mapping (str (name type) ":" (name platform)))))

(defn can-provision?
  "Check if a template exists for this type+platform combination."
  [type platform]
  (boolean
    (when-let [cfg (prov-config)]
      (when-let [tkey (type-platform->template-key type platform)]
        (get-in cfg [:templates tkey])))))

(defn- template-for
  "Get the provisioning template for a type+platform."
  [type platform]
  (when-let [cfg (prov-config)]
    (when-let [tkey (type-platform->template-key type platform)]
      (when-let [tmpl (get-in cfg [:templates tkey])]
        (assoc tmpl :key tkey)))))

;; ---------------------------------------------------------------------------
;; Rune + IP + Port allocation
;; ---------------------------------------------------------------------------

(defn- runes-in-use
  "Scan existing resources for runes already in use by a given platform prefix.
   Parses container names like 'smithr-android-fe' to extract 'fe'."
  [container-prefix]
  (let [prefix (str container-prefix "-")]
    (->> (state/resources)
         (map :container)
         (filter #(when % (str/starts-with? % prefix)))
         (map #(subs % (count prefix)))
         (into #{}))))

(defn- container-prefix-for
  "Determine the container name prefix for a template key.
   e.g. :android-phone -> 'smithr-android'
        :android-build -> 'smithr-android-build'
        :macos-vm -> 'smithr-xcode'
        :ios-phone -> 'smithr-ios'"
  [template-key]
  (case template-key
    :android-phone "smithr-android"
    :android-build "smithr-android-build"
    :macos-vm      "smithr-xcode"
    :ios-phone     "smithr-ios"
    (str "smithr-" (name template-key))))

(defn- ip-range-key
  "Map template key to IP range config key."
  [template-key]
  (case template-key
    :android-phone :android
    :android-build :android-build
    :macos-vm      :macos
    :ios-phone     :macos
    :android))

(defn allocate-rune-ip!
  "Find the next free rune and compute IP/ports for a template.
   Returns {:rune 'thurs' :ip '10.21.0.32' :offset 2 :ports {...}} or nil."
  [template-key]
  (let [cfg (prov-config)
        rune-pool (:rune-pool cfg)
        prefix (container-prefix-for template-key)
        used (runes-in-use prefix)
        ;; Also check runes used by parent template (ios shares runes with xcode)
        parent-used (when (= template-key :ios-phone)
                      (runes-in-use "smithr-xcode"))
        all-used (if parent-used (into used parent-used) used)
        free-rune (first (remove all-used rune-pool))]
    (when-not free-rune
      (log/error "No free runes for" template-key "- all" (count rune-pool) "in use")
      nil)
    (when free-rune
      (let [rune-idx (.indexOf rune-pool free-rune)
            ip-ranges (:ip-ranges cfg)
            ip-key (ip-range-key template-key)
            {:keys [base start]} (get ip-ranges ip-key)
            ip (str base "." (+ start rune-idx))
            port-ranges (:port-ranges cfg)
            ports (when (= template-key :android-phone)
                    {:adb   (+ (:start (:android-adb port-ranges) 5555) rune-idx)
                     :vnc   (+ (:start (:android-vnc port-ranges) 5900) rune-idx)
                     :novnc (+ (:start (:android-novnc port-ranges) 6080) rune-idx)})]
        {:rune free-rune
         :ip ip
         :offset rune-idx
         :ports ports}))))

;; ---------------------------------------------------------------------------
;; Compose up / down
;; ---------------------------------------------------------------------------

(defn- resolve-env
  "Resolve the env-template for a template, filling in :auto values
   from the allocated rune/ip/ports.
   Uses suffix matching to avoid ambiguity (e.g. VNC_PORT vs NOVNC_PORT)."
  [env-template allocation]
  (into {}
        (map (fn [[k v]]
               (if (= v :auto)
                 (cond
                   (str/ends-with? k "_RUNE")      [k (:rune allocation)]
                   (str/ends-with? k "_IP")         [k (:ip allocation)]
                   (str/ends-with? k "_ADB_PORT")   [k (str (get-in allocation [:ports :adb]))]
                   (str/ends-with? k "_NOVNC_PORT") [k (str (get-in allocation [:ports :novnc]))]
                   (str/ends-with? k "_VNC_PORT")   [k (str (get-in allocation [:ports :vnc]))]
                   :else [k ""])
                 [k (str v)])))
        env-template))

(defn compose-up!
  "Run docker compose up for the given compose files with env vars.
   Returns the rune on success, nil on failure."
  [compose-files env-map rune]
  (let [project-name (str "smithr-prov-" rune)
        full-env (assoc env-map
                        "SMITHR_PROVISIONED" "true"
                        "COMPOSE_PROJECT_NAME" project-name)
        file-args (mapcat (fn [f] ["-f" f]) compose-files)
        cmd (vec (concat ["docker" "compose"]
                         file-args
                         ["up" "-d" "--no-recreate"]))
        pb (ProcessBuilder. ^java.util.List cmd)]
    (.directory pb (java.io.File. (System/getProperty "user.dir")))
    (let [pb-env (.environment pb)]
      (doseq [[k v] full-env]
        (.put pb-env k v)))
    (.redirectErrorStream pb true)
    (log/info "Provisioning:" (str/join " " cmd))
    (log/info "Provisioning env:" (pr-str (select-keys full-env
                                                       (filter #(str/starts-with? % "SMITHR_")
                                                               (keys full-env)))))
    (try
      (let [proc (.start pb)
            output (slurp (.getInputStream proc))
            exit (.waitFor proc)]
        (log/info "Compose up output:" (str/trim output))
        (if (zero? exit)
          (do (log/info "Compose up succeeded for rune" rune)
              rune)
          (do (log/error "Compose up failed for rune" rune "exit:" exit)
              nil)))
      (catch Exception e
        (log/error "Compose up exception for rune" rune ":" (.getMessage e))
        nil))))

(defn- compose-down!
  "Run docker compose down to remove a provisioned container.
   Uses the same compose files and env vars used during provisioning."
  [compose-files env-map rune]
  (let [project-name (str "smithr-prov-" rune)
        full-env (assoc env-map "COMPOSE_PROJECT_NAME" project-name)
        file-args (mapcat (fn [f] ["-f" f]) compose-files)
        cmd (vec (concat ["docker" "compose"]
                         file-args
                         ["down" "--remove-orphans"]))
        pb (ProcessBuilder. ^java.util.List cmd)]
    (.directory pb (java.io.File. (System/getProperty "user.dir")))
    (let [pb-env (.environment pb)]
      (doseq [[k v] full-env]
        (.put pb-env k v)))
    (.redirectErrorStream pb true)
    (log/info "Deprovisioning:" (str/join " " cmd))
    (try
      (let [proc (.start pb)
            output (slurp (.getInputStream proc))
            exit (.waitFor proc)]
        (if (zero? exit)
          (log/info "Compose down succeeded for rune" rune)
          (log/error "Compose down failed for rune" rune "exit:" exit "output:" output))
        (zero? exit))
      (catch Exception e
        (log/error "Compose down exception for rune" rune ":" (.getMessage e))
        false))))

;; ---------------------------------------------------------------------------
;; Health check polling
;; ---------------------------------------------------------------------------

(defn wait-for-healthy!
  "Poll Docker healthcheck status for a container until healthy or timeout.
   Uses docker inspect, not Smithr state (state updates async via events).
   Returns true when healthy, false on timeout."
  [container-name timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (log/info "Waiting for" container-name "to become healthy (timeout:" (/ timeout-ms 1000) "s)")
    (loop [attempt 1]
      (if (> (System/currentTimeMillis) deadline)
        (do (log/error "Health timeout for" container-name "after" attempt "attempts")
            false)
        (let [{:keys [exit out]} (shell/sh "docker" "inspect"
                                           "--format" "{{.State.Health.Status}}"
                                           container-name)]
          (if (and (zero? exit) (= (str/trim out) "healthy"))
            (do (log/info container-name "is healthy after" attempt "checks")
                true)
            (do (when (zero? (mod attempt 10))
                  (log/info "Still waiting for" container-name
                            "health:" (str/trim (str out))
                            "attempt:" attempt))
                (Thread/sleep 3000)
                (recur (inc attempt)))))))))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn ensure-resource!
  "Provision a resource matching type+platform if no warm one exists.
   Returns the resource ID if provisioning succeeded, nil otherwise.
   Blocks until the container is healthy or timeout."
  [{:keys [type platform]}]
  (let [type (keyword type)
        platform (keyword platform)]
    (when-not (prov-config)
      (log/debug "Provisioning not configured — skipping")
      nil)
    (when (prov-config)
      (let [tmpl (template-for type platform)]
        (when-not tmpl
          (log/debug "No provisioning template for" type platform)
          nil)
        (when tmpl
          (let [allocation (allocate-rune-ip! (:key tmpl))]
            (when-not allocation
              (log/warn "Cannot allocate rune/IP for" (:key tmpl))
              nil)
            (when allocation
              (let [env-map (resolve-env (:env-template tmpl) allocation)
                    rune (:rune allocation)
                    prefix (container-prefix-for (:key tmpl))
                    container-name (str prefix "-" rune)]
                (log/info "Provisioning" (:key tmpl) "rune:" rune
                          "ip:" (:ip allocation) "container:" container-name)
                (state/record-event! "provision"
                  {:template (name (:key tmpl))
                   :rune rune
                   :container container-name})
                (if (compose-up! (:compose-files tmpl) env-map rune)
                  ;; Wait for healthy
                  (let [timeout-ms (* 1000 (get (prov-config) :health-timeout-seconds 300))]
                    (if (wait-for-healthy! container-name timeout-ms)
                      ;; Docker events will have registered the resource by now.
                      ;; Find the resource ID.
                      (let [resource-id (some (fn [r]
                                                (when (= (:container r) container-name)
                                                  (:id r)))
                                              (state/resources))]
                        (if resource-id
                          (do (log/info "Provisioned resource:" resource-id)
                              resource-id)
                          ;; Resource not in state yet — wait a bit for event processing
                          (do (Thread/sleep 2000)
                              (let [resource-id (some (fn [r]
                                                        (when (= (:container r) container-name)
                                                          (:id r)))
                                                      (state/resources))]
                                (if resource-id
                                  (do (log/info "Provisioned resource (delayed):" resource-id)
                                      resource-id)
                                  (do (log/error "Container" container-name
                                                 "healthy but not in state — event subscription issue?")
                                      nil))))))
                      ;; Not healthy — deprovision
                      (do (log/error "Provisioned container" container-name "failed health check")
                          (state/record-event! "provision-failed"
                            {:template (name (:key tmpl))
                             :rune rune
                             :container container-name
                             :reason "health-timeout"})
                          (future (compose-down! (:compose-files tmpl) env-map rune))
                          nil)))
                  ;; Compose up failed
                  (do (state/record-event! "provision-failed"
                        {:template (name (:key tmpl))
                         :rune rune
                         :container container-name
                         :reason "compose-up-failed"})
                      nil))))))))))

;; ---------------------------------------------------------------------------
;; Deprovision (single resource)
;; ---------------------------------------------------------------------------

(defn deprovision!
  "Stop and remove an on-demand container.
   Only acts on resources with :provisioned? true."
  [resource-id]
  (let [resource (state/resource resource-id)]
    (when (and resource (:provisioned? resource))
      (let [container (:container resource)
            ;; Extract rune from container name
            rune (when container
                   (last (str/split container #"-")))
            type (:type resource)
            platform (:platform resource)
            tmpl (template-for type platform)]
        (log/info "Deprovisioning" resource-id "container:" container)
        (state/record-event! "deprovision"
          {:resource resource-id
           :container container})
        (if (and tmpl rune)
          ;; Use compose down for clean teardown
          (let [allocation (when container
                             ;; Reconstruct minimal allocation for env resolution
                             {:rune rune :ip "" :ports {}})
                env-map (when allocation
                          (resolve-env (:env-template tmpl) allocation))]
            (compose-down! (:compose-files tmpl) env-map rune))
          ;; Fallback: just docker stop + rm
          (do
            (log/info "No template for" type platform "- using docker stop/rm")
            (shell/sh "docker" "stop" container)
            (shell/sh "docker" "rm" "-f" container)))))))

;; ---------------------------------------------------------------------------
;; Idle GC for provisioned resources
;; ---------------------------------------------------------------------------

(defn gc-idle-provisioned!
  "Find provisioned resources that have been warm (idle) longer than
   idle-timeout-seconds and deprovision them.
   Pre-started containers (provisioned?=false) are never affected."
  [idle-timeout-seconds]
  (let [now (Instant/now)
        cutoff (.minus now (Duration/ofSeconds idle-timeout-seconds))
        idle (->> (state/resources)
                  (filter :provisioned?)
                  (filter #(= (:status %) :warm))
                  (filter #(when-let [t (:updated-at %)]
                             (.isBefore t cutoff))))]
    (doseq [r idle]
      (log/info "GC: idle provisioned resource" (:id r)
                "idle since" (:updated-at r) "- deprovisioning")
      (state/record-event! "gc"
        {:resource (:id r)
         :container (:container r)
         :reason "idle-provisioned"})
      (future (deprovision! (:id r))))
    (count idle)))

;; ---------------------------------------------------------------------------
;; Catalogue
;; ---------------------------------------------------------------------------

(defn get-template
  "Look up a provisioning template by key. Returns nil if not configured."
  [template-key]
  (when-let [cfg (prov-config)]
    (get-in cfg [:templates (keyword template-key)])))

(defn provisioning-enabled?
  "True if provisioning is configured."
  []
  (some? (prov-config)))

(defn catalogue
  "Return the provisioning catalogue — what templates are available
   and what resources are currently running."
  []
  (let [cfg (prov-config)]
    (when cfg
      (let [templates (->> (:templates cfg)
                           (map (fn [[k tmpl]]
                                  (merge {:key (name k)
                                          :description (:description tmpl "")
                                          :boot_time_seconds (:boot-time-seconds tmpl 60)}
                                         (:catalogue tmpl))))
                           (sort-by :key)
                           vec)
            resources (->> (state/resources)
                           (map (fn [r]
                                  {:id (:id r)
                                   :type (name (:type r))
                                   :platform (name (:platform r))
                                   :substrate (:substrate r)
                                   :model (:model r)
                                   :status (name (:status r))
                                   :provisioned (:provisioned? r false)
                                   :host (:host r)}))
                           (sort-by :id)
                           vec)]
        {:templates templates
         :active_resources resources}))))
