;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

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
  ;;                  :container-name String :platform String
  ;;                  :original-settings {[ns key] orig-val}}
  ;; NOTE: This atom is volatile — original-settings are lost on Smithr
  ;; restart. If Smithr restarts while a device is connected, the CI
  ;; settings will be re-applied but the true original values (from before
  ;; the first registration) are lost. See #70 for persistence.
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

(defn- slugify
  "Turn a device name into a DNS-safe container name slug.
   e.g. \"Jules\u2019 iPhone 12 Pro Max\" → \"jules-iphone-12-pro-max\""
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[\u0027\u2018\u2019\u0060\u00B4]" "")  ; straight/curly quotes, backtick, acute
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn- container-name-for
  "Docker container name for a physical device marker.
   Uses slugified device-name for human-readable names,
   falls back to device-id if name is unavailable or 'Unknown'."
  [device]
  (let [dn (:device-name device)
        slug (when (and dn (not= dn "Unknown"))
               (let [s (slugify dn)]
                 (when (and (seq s) (not= s "unknown")) s)))]
    (str "smithr-" (or slug (device-id-key device)))))

(defn- start-android-bridge!
  "Start adb forward + socat for a physical Android device.
   adb forward binds localhost only, socat makes it reachable on 0.0.0.0.
   Returns {:fwd-port N :bridge-port M :bridge-process Process} or nil."
  [serial]
  (let [fwd-port (find-free-port)
        bridge-port (find-free-port)]
    (log/info "Starting Android bridge:" serial
              "adb-forward:" fwd-port "socat:" bridge-port)
    ;; Enable TCP mode so the forward target (device:5555) is reachable.
    ;; Without this, adb forward tunnels to port 5555 but nothing listens there.
    (shell/sh "adb" "-s" serial "tcpip" "5555")
    (Thread/sleep 2000) ;; wait for ADB daemon restart
    (shell/sh "adb" "-s" serial "wait-for-device")
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
  "Create a wrapper Docker container on smithr-network for a physical device.
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
                        "--label" (str "smithr.resource.pool=" platform)
                        "--label" "smithr.resource.substrate=physical"
                        "--label" (str "smithr.resource.model=" (:model device))
                        "--label" (str "smithr.resource.device-name=" (:device-name device))
                        "--label" (str "smithr.resource.connect-host=" host-label)
                        "--label" (str "smithr.resource.connect-port=" bridge-port)]
                 (= platform "android")
                 (into ["--label" (str "smithr.resource.serial=" (:serial device))])
                 (= platform "ios")
                 (into ["--label" (str "smithr.resource.udid=" (:udid device))
                         "--label" "smithr.resource.rsd-port=65000"]))]
    (if (wrapper-container-exists? cname)
      (do (log/debug "Wrapper container already exists:" cname)
          cname)
      (let [;; Mount host's ADB key so healthcheck uses the same RSA key
            ;; the phone already authorized (prevents repeated auth prompts)
            home-dir (System/getProperty "user.home")
            adb-dir (str home-dir "/.android")
            ;; Mount SSH pubkeys for near-side access (tools run on the bridge)
            ;; Primary key: host's default SSH key (for direct access)
            ssh-pubkey (first (filter #(.isFile %)
                                [(java.io.File. (str home-dir "/.ssh/id_ed25519.pub"))
                                 (java.io.File. (str home-dir "/.ssh/id_rsa.pub"))]))
            ;; Sidecar key: id_macos / sandbox-macos-access (used by app/maestro sidecars)
            sidecar-pubkey (let [f (java.io.File. (str home-dir "/.ssh/id_ed25519_sandbox_macos.pub"))]
                             (when (.isFile f) f))
            ios? (= platform "ios")
            cmd (cond-> ["docker" "run" "-d"
                          "--name" cname
                          "--network" "smithr-network"
                          "--restart" "unless-stopped"
                          "-v" (str adb-dir ":/root/.android:ro,z")
                          "-e" (str "PLATFORM=" platform)
                          "-e" (str "BRIDGE_PORT=" bridge-port)
                          "-e" (str "BRIDGE_HOST=10.21.0.1")
                          "-e" (str "SERIAL=" device-key)]
                  ;; SSH pubkey for near-side access
                  ssh-pubkey (into ["-v" (str (.getAbsolutePath ssh-pubkey)
                                              ":/root/.ssh/authorized_keys.mount:ro,z")])
                  ;; Sidecar SSH pubkey (app/maestro containers use id_macos)
                  sidecar-pubkey (into ["-v" (str (.getAbsolutePath sidecar-pubkey)
                                                  ":/root/.ssh/sidecar_key.pub:ro,z")])
                  ;; iOS: RSD tunnel needs usbmuxd + TUN capability + tunnel package
                  ;; Maestro distribution + signed driver apps for physical iOS E2E
                  ios? (into ["--cap-add" "net_admin"
                              "--device" "/dev/net/tun"
                              "-v" "/var/run/usbmuxd:/var/run/usbmuxd:z"
                              "-v" "/tmp/py_ios_rsd_tunnel:/opt/py_ios_rsd_tunnel:ro,z"
                              "-v" "/srv/shared/images/maestro:/opt/maestro:ro,z"
                              "-v" "/srv/shared/images/maestro-driver-signed:/opt/driver-apps:z"])
                  true (into labels)
                  true (conj "smithr-phone-bridge:latest"))
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
;; CI-friendly device configuration (automated setup / teardown)
;; ---------------------------------------------------------------------------

(def ^:private ci-android-settings
  "Android settings to apply for CI. Each entry is [namespace key ci-value].
   Original values are saved per-device and restored on teardown."
  [["global" "window_animation_scale"       "0"]
   ["global" "transition_animation_scale"    "0"]
   ["global" "animator_duration_scale"       "0"]
   ["global" "stay_on_while_plugged_in"      "3"]    ;; AC + USB
   ["system" "accelerometer_rotation"        "0"]    ;; lock rotation
   ["global" "heads_up_notifications_enabled" "0"]]) ;; suppress popups

(defn- adb-get-setting
  "Read a setting value from a physical Android device."
  [serial namespace key]
  (try
    (let [{:keys [exit out]} (shell/sh "adb" "-s" serial "shell"
                                       "settings" "get" namespace key)]
      (when (zero? exit)
        (str/trim out)))
    (catch Exception _ nil)))

(defn- adb-put-setting!
  "Write a setting value on a physical Android device.
   Returns true on success, false on failure (e.g. permission denied)."
  [serial namespace key value]
  (try
    (let [{:keys [exit]} (shell/sh "adb" "-s" serial "shell"
                                   "settings" "put" namespace key value)]
      (zero? exit))
    (catch Exception _ false)))

(defn- configure-android-for-ci!
  "Apply CI-friendly settings to a physical Android device.
   Saves original values and returns them as a map for later restoration."
  [serial]
  (log/info "Configuring Android device" serial "for CI")
  (let [originals (into {}
                    (for [[ns key ci-val] ci-android-settings]
                      (let [orig (adb-get-setting serial ns key)]
                        (if (adb-put-setting! serial ns key ci-val)
                          (do (log/debug "  set" ns "/" key ":" orig "→" ci-val)
                              [[ns key] orig])
                          (do (log/warn "  failed to set" ns "/" key
                                        "(permission denied?)")
                              [[ns key] nil])))))]
    ;; Also try to disable lock screen (best-effort)
    (try
      (let [{:keys [exit]} (shell/sh "adb" "-s" serial "shell"
                                     "locksettings" "set-disabled" "true")]
        (when (zero? exit)
          (log/debug "  lock screen disabled")))
      (catch Exception _ nil))
    ;; Switch to Simple Keyboard if installed — Gboard's clipboard popup
    ;; breaks Maestro E2E and appops deny doesn't stick on OPPO/ColorOS.
    (try
      (let [{:keys [exit out]} (shell/sh "adb" "-s" serial "shell"
                                         "ime" "set"
                                         "rkr.simplekeyboard.inputmethod/.latin.LatinIME")]
        (if (and (zero? exit) (re-find #"selected" (str out)))
          (log/debug "  switched to Simple Keyboard")
          (log/debug "  Simple Keyboard not installed, keeping default IME")))
      (catch Exception _ nil))
    originals))

(defn- restore-android-settings!
  "Restore a physical Android device's original settings saved during CI setup."
  [serial originals]
  (when (seq originals)
    (log/info "Restoring original settings on Android device" serial)
    (doseq [[[ns key] orig-val] originals]
      (when (and orig-val (not= orig-val "null"))
        (if (adb-put-setting! serial ns key orig-val)
          (log/debug "  restored" ns "/" key "→" orig-val)
          (log/warn "  failed to restore" ns "/" key))))
    ;; Re-enable lock screen
    (try
      (shell/sh "adb" "-s" serial "shell" "locksettings" "set-disabled" "false")
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Near-side worker containers (Maestro + ADB colocated with phone)
;; ---------------------------------------------------------------------------

(defn- worker-image-available?
  "Check if the smithr-phone-worker Docker image exists."
  []
  (zero? (:exit (shell/sh "docker" "image" "inspect" "smithr-phone-worker:latest"))))

(defn- create-worker-container!
  "Create a near-side worker container for a physical Android device.
   The worker runs Maestro + ADB on smithr-network, connected to the bridge's
   ADB server. CI invokes Maestro here instead of through the SSH tunnel."
  [bridge-cname device]
  (let [worker-name (str bridge-cname "-worker")
        home-dir (System/getProperty "user.home")
        ssh-pubkey (first (filter #(.isFile %)
                            [(java.io.File. (str home-dir "/.ssh/smithr_worker.pub"))
                             (java.io.File. (str home-dir "/.ssh/id_ed25519.pub"))
                             (java.io.File. (str home-dir "/.ssh/id_rsa.pub"))]))
        cmd (cond-> ["docker" "run" "-d"
                     "--name" worker-name
                     "--network" "smithr-network"
                     "--restart" "unless-stopped"
                     "-e" (str "PHONE_HOST=" bridge-cname)
                     "-e" (str "PLATFORM=" (:platform device))
                     "-v" (str home-dir "/.android:/root/.android:ro,z")]
              ssh-pubkey
              (into ["-v" (str (.getAbsolutePath ssh-pubkey)
                               ":/root/.ssh/authorized_keys.mount:ro,z")])
              true
              (into ["--label" (str "smithr.worker-for=" bridge-cname)
                     "smithr-phone-worker:latest"]))]
    (if (wrapper-container-exists? worker-name)
      (log/debug "Worker already exists:" worker-name)
      (let [{:keys [exit err]} (apply shell/sh cmd)]
        (if (zero? exit)
          (log/info "Created worker container:" worker-name)
          (log/warn "Failed to create worker:" worker-name err))))))

(defn- remove-worker-container!
  "Remove the worker container associated with a bridge container."
  [bridge-cname]
  (let [worker-name (str bridge-cname "-worker")]
    (when (wrapper-container-exists? worker-name)
      (let [{:keys [exit]} (shell/sh "docker" "rm" "-f" worker-name)]
        (if (zero? exit)
          (log/info "Removed worker container:" worker-name)
          (log/debug "Worker container removal skipped:" worker-name))))))

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

(defn- cleanup-orphan-bridges!
  "Remove wrapper containers for physical devices that are no longer connected.
   Handles the case where Smithr restarts after a phone was unplugged — the
   old container is still running but not tracked in device-bridges."
  [scanned-keys]
  (let [{:keys [out exit]} (shell/sh "docker" "ps" "-a"
                                     "--filter" "label=smithr.resource.substrate=physical"
                                     "--format" "{{.Names}}")
        containers (when (zero? exit)
                     (remove str/blank? (str/split-lines out)))]
    (doseq [cname containers]
      ;; Check if any scanned device would produce this container name
      (let [tracked? (some (fn [dk] (= cname (:container-name (get @device-bridges dk))))
                           scanned-keys)
            ;; Also check if any current device matches by inspecting labels
            has-device? (when-not tracked?
                          (let [{lout :out lexit :exit}
                                (shell/sh "docker" "inspect" cname
                                          "--format"
                                          "{{index .Config.Labels \"smithr.resource.serial\"}}|{{index .Config.Labels \"smithr.resource.udid\"}}")]
                            (when (zero? lexit)
                              (let [ids (remove str/blank? (str/split (str/trim lout) #"\|"))]
                                (some scanned-keys ids)))))]
        (when (and (not tracked?) (not has-device?))
          (log/info "Removing orphan bridge container:" cname)
          ;; Get platform label before removing (need it for state cleanup)
          (let [platform (let [{lout :out lexit :exit}
                               (shell/sh "docker" "inspect" cname "--format"
                                         "{{index .Config.Labels \"smithr.resource.platform\"}}")]
                           (when (zero? lexit) (str/trim lout)))]
            (remove-worker-container! cname)
            (remove-wrapper-container! cname)
            ;; Also remove from Smithr state (Docker events may not fire during startup)
            (when platform
              (doseq [h (state/hosts)]
                (state/remove-resource! (str (:label h) ":" platform ":" cname))))))))))

(defn- cleanup-stale-physical-resources!
  "Remove state entries for physical resources on THIS host whose containers
   no longer exist. Only checks the local Docker daemon — cross-host physical
   resources are managed by the remote host's Smithr instance."
  [host-label scanned-keys]
  (let [container-set (let [{:keys [out exit]}
                            (shell/sh "docker" "ps" "-a"
                                      "--filter" "label=smithr.resource.substrate=physical"
                                      "--format" "{{.Names}}")]
                        (when (zero? exit)
                          (set (remove str/blank? (str/split-lines out)))))]
    (doseq [r (state/resources)]
      (when (and (= "physical" (:substrate r))
                 (= host-label (:host r))
                 (not (contains? container-set (:container r))))
        (log/info "Removing stale physical resource from state:" (:id r))
        (state/remove-resource! (:id r))))))

(defn register-devices!
  "Scan for physical devices, create host bridges and wrapper containers.
   The wrapper containers carry smithr labels on smithr-network, so
   Docker event subscription on all hosts discovers them automatically.
   Bridge = adb forward + socat (Android) or iproxy (iOS) on the host.
   Also applies CI-friendly settings (animations off, stay awake, etc.)
   and saves originals for restoration on device removal."
  [host-label]
  (let [{:keys [devices]} (scan-all-devices host-label)
        scanned-keys (set (map device-id-key devices))
        current-bridges @device-bridges]
    ;; Clean up orphan containers from previous runs
    (cleanup-orphan-bridges! scanned-keys)
    ;; Clean up state entries for containers that no longer exist (local only)
    (cleanup-stale-physical-resources! host-label scanned-keys)
    ;; Register new devices
    (doseq [device devices]
      (let [dk (device-id-key device)
            existing (get current-bridges dk)]
        (if (and existing (bridge-alive? existing))
          ;; Bridge alive — check if container needs renaming (e.g. device name
          ;; became available after trust handshake completed)
          (let [desired-cname (container-name-for device)
                current-cname (:container-name existing)]
            (when (and current-cname (not= current-cname desired-cname))
              (log/info "Renaming bridge container:" current-cname "\u2192" desired-cname)
              (remove-wrapper-container! current-cname)
              (when (create-wrapper-container! host-label device (:bridge-port existing))
                (swap! device-bridges assoc dk
                       (assoc existing :container-name desired-cname))))
            ;; Recreate worker if missing (bridge alive but worker deleted)
            (let [cname (or current-cname desired-cname)]
              (when (and (= (:platform device) "android")
                         (worker-image-available?)
                         (not (wrapper-container-exists? (str cname "-worker"))))
                (log/info "Worker missing for" cname "- recreating")
                (create-worker-container! cname device))))
          ;; Bridge missing or dead — (re)create
          (do (when existing
            (log/info "Bridge dead for" dk "— recreating")
            (stop-bridge! dk existing))
          (let [bridge (case (:platform device)
                         "android" (start-android-bridge! (:serial device))
                         "ios"     (start-ios-bridge! (:udid device))
                         nil)]
            (when bridge
              (let [cname (container-name-for device)]
                ;; Remove stale wrapper container so it gets recreated with
                ;; the new bridge port. After Smithr restart, the old container
                ;; still points to the dead bridge port — must recreate.
                (when (wrapper-container-exists? cname)
                  (remove-wrapper-container! cname))
                (when-let [created-cname (create-wrapper-container! host-label device (:bridge-port bridge))]
                  ;; Create near-side worker for Android physical devices
                  (when (and (= (:platform device) "android")
                             (worker-image-available?))
                    (create-worker-container! created-cname device))
                  ;; Apply CI-friendly settings, save originals for teardown
                  (let [originals (case (:platform device)
                                   "android" (configure-android-for-ci!
                                               (:serial device))
                                   nil)]
                    (swap! device-bridges assoc dk
                           (assoc bridge
                                  :container-name cname
                                  :platform (:platform device)
                                  :original-settings originals)))))))))))
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
              ;; Restore original settings before tearing down
              (when (and (= (:platform bridge) "android")
                         (:original-settings bridge))
                (restore-android-settings! dk (:original-settings bridge)))
              (stop-bridge! dk bridge)
              (when cname
                (remove-worker-container! cname)
                (remove-wrapper-container! cname))
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
  "Stop all device bridges and restore original settings (called on Smithr shutdown)."
  []
  (doseq [[dk bridge] @device-bridges]
    (log/info "Shutting down bridge for" dk)
    ;; Restore original settings before shutdown
    (when (and (= (:platform bridge) "android")
               (:original-settings bridge))
      (restore-android-settings! dk (:original-settings bridge)))
    (stop-bridge! dk bridge)
    (when-let [cname (:container-name bridge)]
      (remove-worker-container! cname)
      (remove-wrapper-container! cname)))
  (reset! device-bridges {}))
