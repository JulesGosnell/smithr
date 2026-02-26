(ns smithr.devices
  "USB device discovery and registration.
   Scans for physical Android (adb) and iOS (libimobiledevice) devices
   plugged into any host, creates marker Docker containers so they're
   visible via cross-host Docker event subscription — identical to
   emulated devices from the outside."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [smithr.state :as state])
  (:import [java.net ServerSocket]))

;; ---------------------------------------------------------------------------
;; Bridge state — tracks host-side processes per physical device
;; ---------------------------------------------------------------------------

(defonce ^:private device-bridges
  ;; serial/udid -> {:fwd-port N :bridge-port M :bridge-process Process
  ;;                  :container-name String :platform String}
  (atom {}))

(defn- find-free-port
  "Find an available TCP port by briefly binding a ServerSocket."
  []
  (with-open [sock (ServerSocket. 0)]
    (.getLocalPort sock)))

;; ---------------------------------------------------------------------------
;; Android device scanning (adb)
;; ---------------------------------------------------------------------------

(defn- parse-adb-devices
  "Parse `adb devices` output into a list of serial numbers.
   Filters for 'device' status (not offline/unauthorized)."
  [output]
  (->> (str/split-lines output)
       (drop 1) ;; skip header
       (map str/trim)
       (filter #(str/ends-with? % "\tdevice"))
       (map #(first (str/split % #"\t")))
       (remove str/blank?)))

(defn- adb-device-model
  "Get the model name of an Android device by serial."
  [serial]
  (try
    (let [{:keys [exit out]} (shell/sh "adb" "-s" serial "shell"
                                       "getprop" "ro.product.model")]
      (when (zero? exit)
        (str/trim out)))
    (catch Exception _ nil)))

(defn- adb-device-name
  "Get the user-facing device name (e.g. 'OPPO A53') from settings."
  [serial]
  (try
    (let [{:keys [exit out]} (shell/sh "adb" "-s" serial "shell"
                                       "settings" "get" "global" "device_name")]
      (when (and (zero? exit) (not (str/blank? out))
                 (not= (str/trim out) "null"))
        (str/trim out)))
    (catch Exception _ nil)))

(defn scan-android-devices
  "Scan for physical Android devices via adb.
   Filters out emulator serials (emulator-NNNN) and tunnel endpoints
   (localhost:PORT, IP:PORT) — only real USB-connected devices qualify.
   Returns a list of {:serial :platform :model :substrate} maps."
  []
  (try
    (let [{:keys [exit out]} (shell/sh "adb" "devices")]
      (if (zero? exit)
        (let [serials (->> (parse-adb-devices out)
                           ;; Filter out emulators (emulator-NNNN)
                           (remove #(str/starts-with? % "emulator-"))
                           ;; Filter out ADB tunnel endpoints (host:port)
                           (remove #(str/includes? % ":")))]
          (doall
            (for [serial serials]
              (let [model (or (adb-device-model serial) "Unknown")
                    dname (adb-device-name serial)]
                {:serial serial
                 :platform "android"
                 :substrate "physical"
                 :model model
                 :device-name (or dname model)}))))
        (do (log/debug "adb devices failed, exit:" exit)
            [])))
    (catch Exception e
      (log/debug "adb not available:" (.getMessage e))
      [])))

;; ---------------------------------------------------------------------------
;; iOS device scanning (libimobiledevice)
;; ---------------------------------------------------------------------------

(defn- parse-idevice-list
  "Parse `idevice_id -l` output into a list of UDIDs."
  [output]
  (->> (str/split-lines output)
       (map str/trim)
       (remove str/blank?)))

(defn- idevice-info-key
  "Get a single key from ideviceinfo for a device."
  [udid key]
  (try
    (let [{:keys [exit out]} (shell/sh "ideviceinfo" "-u" udid "-k" key)]
      (when (and (zero? exit) (not (str/blank? out)))
        (str/trim out)))
    (catch Exception _ nil)))

(defn- idevice-model
  "Get the marketing model name of an iOS device (e.g. 'iPhone 12 Pro Max').
   Falls back to ProductType (e.g. 'iPhone13,4') if MarketingName unavailable."
  [udid]
  (or (idevice-info-key udid "MarketingName")
      (idevice-info-key udid "ProductType")))

(defn- idevice-name
  "Get the user-set device name (e.g. \"Jules' iPhone 12 Pro Max\")."
  [udid]
  (idevice-info-key udid "DeviceName"))

(defn scan-ios-devices
  "Scan for physical iOS devices via libimobiledevice.
   Returns a list of {:udid :platform :model :substrate} maps."
  []
  (try
    (let [{:keys [exit out]} (shell/sh "idevice_id" "-l")]
      (if (zero? exit)
        (let [udids (parse-idevice-list out)]
          (doall
            (for [udid udids]
              (let [model (or (idevice-model udid) "Unknown")
                    dname (idevice-name udid)]
                {:udid udid
                 :platform "ios"
                 :substrate "physical"
                 :model model
                 :device-name (or dname model)}))))
        (do (log/debug "idevice_id failed, exit:" exit)
            [])))
    (catch Exception e
      (log/debug "libimobiledevice not available:" (.getMessage e))
      [])))

;; ---------------------------------------------------------------------------
;; Host-side bridge management
;; ---------------------------------------------------------------------------

(defn- device-id-key
  "Get the unique key for a device (serial for Android, UDID for iOS)."
  [device]
  (or (:serial device) (:udid device)))

(defn- container-name-for
  "Docker container name for a physical device marker."
  [device]
  (str "physical-" (device-id-key device)))

(defn- start-android-bridge!
  "Start adb forward + socat for a physical Android device.
   adb forward binds localhost only, socat makes it reachable on 0.0.0.0.
   Returns {:fwd-port N :bridge-port M :bridge-process Process} or nil."
  [serial]
  (let [fwd-port (find-free-port)
        bridge-port (find-free-port)]
    (log/info "Starting Android bridge:" serial
              "adb-forward:" fwd-port "socat:" bridge-port)
    (let [{:keys [exit]} (shell/sh "adb" "-s" serial
                                   "forward"
                                   (str "tcp:" fwd-port)
                                   (str "tcp:5555"))]
      (if (zero? exit)
        (let [cmd ["socat"
                   (str "TCP-LISTEN:" bridge-port ",fork,reuseaddr")
                   (str "TCP:localhost:" fwd-port)]
              proc (-> (ProcessBuilder. ^java.util.List cmd)
                       (.redirectErrorStream true)
                       (.start))]
          (Thread/sleep 200)
          (if (.isAlive proc)
            (do (log/info "Android bridge ready:" serial
                          "port" bridge-port "pid" (.pid proc))
                {:fwd-port fwd-port
                 :bridge-port bridge-port
                 :bridge-process proc})
            (do (log/error "socat died immediately for" serial)
                (shell/sh "adb" "-s" serial "forward" "--remove"
                          (str "tcp:" fwd-port))
                nil)))
        (do (log/error "adb forward failed for" serial)
            nil)))))

(defn- start-ios-bridge!
  "Start iproxy for a physical iOS device.
   Returns {:bridge-port N :bridge-process Process} or nil."
  [udid]
  (let [bridge-port (find-free-port)]
    (log/info "Starting iOS bridge:" udid "iproxy port:" bridge-port)
    (try
      (let [cmd ["iproxy" "-u" udid (str bridge-port ":62078")]
            proc (-> (ProcessBuilder. ^java.util.List cmd)
                     (.redirectErrorStream true)
                     (.start))]
        (Thread/sleep 200)
        (if (.isAlive proc)
          (do (log/info "iOS bridge ready:" udid "port" bridge-port "pid" (.pid proc))
              {:bridge-port bridge-port
               :bridge-process proc})
          (do (log/error "iproxy died immediately for" udid)
              nil)))
      (catch Exception e
        (log/error "Failed to start iproxy for" udid ":" (.getMessage e))
        nil))))

(defn- stop-bridge!
  "Stop a device bridge (kill socat/iproxy, remove adb forward)."
  [device-key bridge]
  (when-let [^Process proc (:bridge-process bridge)]
    (try
      (when (.isAlive proc)
        (.destroyForcibly proc)
        (log/info "Bridge process killed:" device-key "pid" (.pid proc)))
      (catch Exception e
        (log/warn "Error killing bridge for" device-key ":" (.getMessage e)))))
  ;; Remove adb forward if applicable
  (when (:fwd-port bridge)
    (let [{:keys [exit]} (shell/sh "adb" "-s" device-key
                                   "forward" "--remove"
                                   (str "tcp:" (:fwd-port bridge)))]
      (if (zero? exit)
        (log/info "ADB forward removed:" device-key "port" (:fwd-port bridge))
        (log/debug "ADB forward --remove skipped for" device-key)))))

(defn- bridge-alive?
  "Check if a device bridge's socat/iproxy process is still running."
  [bridge]
  (when-let [^Process proc (:bridge-process bridge)]
    (.isAlive proc)))

;; ---------------------------------------------------------------------------
;; Wrapper container management
;; ---------------------------------------------------------------------------

(defn- wrapper-container-exists?
  "Check if a Docker container with the given name exists."
  [name]
  (let [{:keys [exit]} (shell/sh "docker" "inspect" name)]
    (zero? exit)))

(defn- create-wrapper-container!
  "Create a marker Docker container on smithr-network for a physical device.
   The container carries smithr labels so both hosts discover it via
   Docker event subscription — making it indistinguishable from an
   emulated device."
  [host-label device bridge-port]
  (let [cname (container-name-for device)
        platform (:platform device)
        device-key (device-id-key device)
        labels (cond-> ["--label" "smithr.managed=true"
                        "--label" "smithr.resource.type=phone"
                        "--label" (str "smithr.resource.platform=" platform)
                        "--label" "smithr.resource.substrate=physical"
                        "--label" (str "smithr.resource.model=" (:model device))
                        "--label" (str "smithr.resource.device-name=" (:device-name device))
                        "--label" (str "smithr.resource.connect-host=" host-label)
                        "--label" (str "smithr.resource.connect-port=" bridge-port)]
                 (= platform "android")
                 (into ["--label" (str "smithr.resource.serial=" (:serial device))])
                 (= platform "ios")
                 (into ["--label" (str "smithr.resource.udid=" (:udid device))]))]
    (if (wrapper-container-exists? cname)
      (do (log/debug "Wrapper container already exists:" cname)
          cname)
      (let [;; Mount host's ADB key so healthcheck uses the same RSA key
            ;; the phone already authorized (prevents repeated auth prompts)
            adb-dir (str (System/getProperty "user.home") "/.android")
            cmd (into ["docker" "run" "-d"
                        "--name" cname
                        "--network" "smithr-network"
                        "--restart" "unless-stopped"
                        "-v" (str adb-dir ":/root/.android:ro,z")
                        "-e" (str "BRIDGE_PORT=" bridge-port)
                        "-e" (str "BRIDGE_HOST=10.21.0.1")
                        "-e" (str "SERIAL=" device-key)]
                      (into labels
                            ["smithr-phone-bridge:latest"]))
            {:keys [exit err]} (apply shell/sh cmd)]
        (if (zero? exit)
          (do (log/info "Created wrapper container:" cname "for" device-key
                        "bridge-port:" bridge-port)
              cname)
          (do (log/error "Failed to create wrapper container:" cname err)
              nil))))))

(defn- remove-wrapper-container!
  "Remove a marker Docker container."
  [cname]
  (let [{:keys [exit]} (shell/sh "docker" "rm" "-f" cname)]
    (if (zero? exit)
      (log/info "Removed wrapper container:" cname)
      (log/debug "Wrapper container removal skipped:" cname))))

;; ---------------------------------------------------------------------------
;; Scan + register (with wrapper containers)
;; ---------------------------------------------------------------------------

(defn scan-all-devices
  "Scan all connected USB devices on the local host.
   Returns {:host host-label :devices [...]}"
  [host-label]
  (let [android (scan-android-devices)
        ios (scan-ios-devices)]
    {:host host-label
     :devices (vec (concat android ios))}))

(defn register-devices!
  "Scan for physical devices, create host bridges and wrapper containers.
   The wrapper containers carry smithr labels on smithr-network, so
   Docker event subscription on all hosts discovers them automatically.
   Bridge = adb forward + socat (Android) or iproxy (iOS) on the host."
  [host-label]
  (let [{:keys [devices]} (scan-all-devices host-label)
        scanned-keys (set (map device-id-key devices))
        current-bridges @device-bridges]
    ;; Register new devices
    (doseq [device devices]
      (let [dk (device-id-key device)
            existing (get current-bridges dk)]
        (when (or (nil? existing) (not (bridge-alive? existing)))
          ;; Bridge missing or dead — (re)create
          (when existing
            (log/info "Bridge dead for" dk "— recreating")
            (stop-bridge! dk existing))
          (let [bridge (case (:platform device)
                         "android" (start-android-bridge! (:serial device))
                         "ios"     (start-ios-bridge! (:udid device))
                         nil)]
            (when bridge
              (let [cname (container-name-for device)]
                ;; Remove stale wrapper container if bridge port changed
                (when (and existing (wrapper-container-exists? cname))
                  (remove-wrapper-container! cname))
                (when (create-wrapper-container! host-label device (:bridge-port bridge))
                  (swap! device-bridges assoc dk
                         (assoc bridge
                                :container-name cname
                                :platform (:platform device))))))))))
    ;; Remove unplugged devices
    (doseq [[dk bridge] current-bridges]
      (when-not (contains? scanned-keys dk)
        (let [cname (:container-name bridge)
              ;; Check if there's an active lease on this device
              resource-id (str host-label ":" (:platform bridge) ":" cname)
              leased? (some #(and (= (:resource-id %) resource-id)
                                  (not (:ended-at %)))
                            (state/leases))]
          (if leased?
            (log/warn "Physical device" dk "unplugged but has active lease!")
            (do
              (log/info "Physical device removed:" dk)
              (stop-bridge! dk bridge)
              (when cname (remove-wrapper-container! cname))
              (swap! device-bridges dissoc dk))))))
    (count devices)))

;; ---------------------------------------------------------------------------
;; Liveness checks for physical devices
;; ---------------------------------------------------------------------------

(defn- check-android-liveness
  "Verify an Android device is still responsive via adb."
  [serial]
  (try
    (let [{:keys [exit out]} (shell/sh "adb" "-s" serial "shell" "echo" "ok")]
      (and (zero? exit) (str/includes? (str out) "ok")))
    (catch Exception _ false)))

(defn- check-ios-liveness
  "Verify an iOS device is still connected via ideviceinfo."
  [udid]
  (try
    (let [{:keys [exit]} (shell/sh "ideviceinfo" "-u" udid)]
      (zero? exit))
    (catch Exception _ false)))

(defn check-device-liveness!
  "Check liveness of physical device resources on the local host only.
   Marks dead devices as :dead."
  [own-host]
  (doseq [r (state/resources)]
    (when (and (= (:substrate r) "physical")
               (= (:host r) own-host))
      (let [alive? (case (:platform r)
                     :android (check-android-liveness
                                (get-in r [:connection :serial]))
                     :ios (check-ios-liveness
                            (get-in r [:connection :udid]))
                     true)]
        (when (and (not alive?) (not= (:status r) :dead))
          (log/warn "Physical device" (:id r) "not responding — marking dead")
          (state/update-resource-status! (:id r) :dead))))))

(defn shutdown-bridges!
  "Stop all device bridges (called on Smithr shutdown)."
  []
  (doseq [[dk bridge] @device-bridges]
    (log/info "Shutting down bridge for" dk)
    (stop-bridge! dk bridge)
    (when-let [cname (:container-name bridge)]
      (remove-wrapper-container! cname)))
  (reset! device-bridges {}))
