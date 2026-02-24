(ns smithr.devices
  "USB device discovery and registration.
   Scans for physical Android (adb) and iOS (libimobiledevice) devices
   plugged into any host, registers them as leasable resources."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [smithr.state :as state])
  (:import [java.time Instant]))

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

(defn- adb-device-wifi-ip
  "Get the WiFi IP address of a physical Android device.
   Does NOT switch to TCP mode — just reads the IP via USB.
   Returns nil if WiFi is not connected or ADB fails."
  [serial]
  (try
    (let [{:keys [exit out]} (shell/sh "adb" "-s" serial "shell"
                                       "ip" "addr" "show" "wlan0")]
      (when (zero? exit)
        (when-let [m (re-find #"inet (\d+\.\d+\.\d+\.\d+)" out)]
          (second m))))
    (catch Exception e
      (log/debug "Failed to get WiFi IP for" serial ":" (.getMessage e))
      nil)))

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
              (let [ip (adb-device-wifi-ip serial)
                    model (or (adb-device-model serial) "Unknown")
                    dname (adb-device-name serial)]
                (cond-> {:serial serial
                         :platform "android"
                         :substrate "physical"
                         :model model
                         :device-name (or dname model)}
                  ip (assoc :wifi-ip ip))))))
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
;; Resource registration
;; ---------------------------------------------------------------------------

(defn- device->resource-id
  "Generate a resource ID for a physical device."
  [host-label device]
  (let [id-suffix (or (:serial device) (:udid device) "unknown")]
    (str host-label ":" (:platform device) ":physical-" id-suffix)))

(defn- device->resource
  "Convert a discovered device into a Smithr resource map."
  [host-label device]
  (let [platform (keyword (:platform device))
        id (device->resource-id host-label device)]
    {:id id
     :type :phone
     :platform platform
     :host host-label
     :status :warm
     :container (str "physical-" (or (:serial device) (:udid device)))
     :substrate "physical"
     :model (:model device)
     :device-name (:device-name device)
     :provisioned? false
     :connection (case platform
                   :android (cond-> {:adb-host (or (:wifi-ip device) "localhost")
                                     :adb-port 5555
                                     :serial (:serial device)}
                              (:wifi-ip device) (assoc :wifi-ip (:wifi-ip device)))
                   :ios     {:udid (:udid device)}
                   {})
     :updated-at (Instant/now)}))

;; ---------------------------------------------------------------------------
;; Scan + register
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
  "Scan for physical devices and register them in state.
   Removes resources for devices that have been unplugged."
  [host-label]
  (let [{:keys [devices]} (scan-all-devices host-label)
        ;; Current physical resources for this host
        current-physical (->> (state/resources)
                              (filter #(= (:host %) host-label))
                              (filter #(= (:substrate %) "physical")))
        current-ids (set (map :id current-physical))
        ;; IDs from the scan
        scanned-ids (set (map #(device->resource-id host-label %) devices))]
    ;; Register new devices
    (doseq [device devices]
      (let [resource (device->resource host-label device)
            existing (state/resource (:id resource))]
        (when-not existing
          (log/info "Discovered physical device:" (:device-name device)
                    "(" (:model device) ")" (:id resource))
          (state/upsert-resource! resource)
          (state/record-event! "device-discovered"
            {:resource (:id resource)
             :platform (:platform device)
             :model (:model device)
             :device-name (:device-name device)
             :substrate "physical"}))))
    ;; Remove unplugged devices (only if not currently leased)
    (doseq [r current-physical]
      (when-not (contains? scanned-ids (:id r))
        (if (#{:leased :shared} (:status r))
          (do (log/warn "Physical device" (:id r) "unplugged but has active lease!")
              (state/update-resource-status! (:id r) :dead))
          (do (log/info "Physical device removed:" (:id r))
              (state/remove-resource! (:id r))
              (state/record-event! "device-removed"
                {:resource (:id r)})))))
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
  "Check liveness of all physical device resources.
   Marks dead devices as :dead."
  []
  (doseq [r (state/resources)]
    (when (= (:substrate r) "physical")
      (let [alive? (case (:platform r)
                     :android (check-android-liveness
                                (get-in r [:connection :serial]))
                     :ios (check-ios-liveness
                            (get-in r [:connection :udid]))
                     true)]
        (when (and (not alive?) (not= (:status r) :dead))
          (log/warn "Physical device" (:id r) "not responding — marking dead")
          (state/update-resource-status! (:id r) :dead))))))
