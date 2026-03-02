(ns smithr.macos
  "macOS user lifecycle management via SSH.
   Creates and deletes per-build user accounts on macOS VMs
   for isolated concurrent build access.
   Same interface as smithr.linux but uses dscl/createhomedir."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [smithr.ssh :as ssh]))

(defn build-username
  "Generate a macOS username for a build lease.
   Uses first 8 characters of the lease ID for uniqueness."
  [lease-id]
  (str "build-" (subs lease-id 0 (min 8 (count lease-id)))))

(defn- ssh-key-path [] (ssh/ssh-key-path))

(defn ssh-exec-raw!
  "Execute a command on a macOS VM via SSH.
   Returns {:exit int :out string :err string}."
  [resource cmd]
  (let [{:keys [ssh-host ssh-port]} (:connection resource)
        key-path (ssh-key-path)
        result (sh "ssh"
                   "-i" key-path
                   "-o" "StrictHostKeyChecking=no"
                   "-o" "ConnectTimeout=10"
                   "-p" (str (or ssh-port 10022))
                   (str "smithr@" ssh-host)
                   cmd)]
    (when (not= 0 (:exit result))
      (log/warn "SSH command failed on" (:id resource)
                "exit:" (:exit result)
                "err:" (str/trim (:err result))))
    result))

;; ---------------------------------------------------------------------------
;; Inline user creation commands (matches linux.clj pattern)
;; ---------------------------------------------------------------------------

(defn- uid-for-username
  "Derive a deterministic UID from a username via CRC32.
   Range: 600-65599. Same username always gets the same UID."
  [username]
  (let [crc (java.util.zip.CRC32.)]
    (.update crc (.getBytes username "UTF-8"))
    (+ 600 (mod (.getValue crc) 65000))))

(defn- create-user-cmd
  "Build a shell command that creates a macOS user with dscl.
   Handles: dscl record, home dir, SSH keys, access group, shell profile.
   Idempotent — repairs existing users with missing SSH keys or home dirs."
  [username]
  (let [uid (uid-for-username username)
        home (str "/Users/" username)]
    (str
      ;; Check if user already exists — if so, just ensure SSH keys and home dir
      "if dscl . -read /Users/" username " UniqueID >/dev/null 2>&1; then "
        "HOME_DIR=$(dscl . -read /Users/" username " NFSHomeDirectory 2>/dev/null | awk '{print $2}'); "
        "HOME_DIR=${HOME_DIR:-" home "}; "
        ;; Fix missing NFSHomeDirectory
        "if [ -z \"$(dscl . -read /Users/" username " NFSHomeDirectory 2>/dev/null | awk '{print $2}')\" ]; then "
          "sudo dscl . -create /Users/" username " NFSHomeDirectory " home "; "
          "HOME_DIR=" home "; "
        "fi; "
        ;; Fix missing home dir
        "if [ ! -d \"$HOME_DIR\" ]; then "
          "sudo mkdir -p \"$HOME_DIR\" && sudo chown " username ":staff \"$HOME_DIR\"; "
        "fi; "
        ;; Fix missing SSH keys
        "if [ ! -f \"$HOME_DIR/.ssh/authorized_keys\" ]; then "
          "sudo mkdir -p \"$HOME_DIR/.ssh\""
          " && sudo cp /Users/smithr/.ssh/authorized_keys \"$HOME_DIR/.ssh/\""
          " && sudo chown -R " username ":staff \"$HOME_DIR/.ssh\""
          " && sudo chmod 700 \"$HOME_DIR/.ssh\""
          " && sudo chmod 600 \"$HOME_DIR/.ssh/authorized_keys\"; "
        "fi; "
        ;; Always sync CoreSimulator RuntimeMap (SDK-to-runtime overrides)
        ;; Try user-level first (has iphonesimulator mapping from ios-sim-boot),
        ;; fall back to system-level (baked in base image, has iphoneos mapping)
        "SMITHR_CORESIM=/Users/smithr/Library/Developer/CoreSimulator; "
        "SYS_CORESIM=/Library/Developer/CoreSimulator; "
        "RTMAP=\"\"; "
        "if [ -f \"$SMITHR_CORESIM/RuntimeMap.plist\" ]; then RTMAP=\"$SMITHR_CORESIM/RuntimeMap.plist\"; "
        "elif [ -f \"$SYS_CORESIM/RuntimeMap.plist\" ]; then RTMAP=\"$SYS_CORESIM/RuntimeMap.plist\"; "
        "fi; "
        "if [ -n \"$RTMAP\" ]; then "
          "sudo mkdir -p \"$HOME_DIR/Library/Developer/CoreSimulator\""
          " && sudo cp \"$RTMAP\" \"$HOME_DIR/Library/Developer/CoreSimulator/\""
          " && sudo chown -R " username ":staff \"$HOME_DIR/Library\"; "
        "fi; "
        "echo \"$HOME_DIR\"; "
      "else "
        ;; Create new user
        "sudo dscl . -create /Users/" username
        " && sudo dscl . -create /Users/" username " UserShell /bin/bash"
        " && sudo dscl . -create /Users/" username " RealName 'Build " username "'"
        " && sudo dscl . -create /Users/" username " UniqueID " uid
        " && sudo dscl . -create /Users/" username " PrimaryGroupID 20"
        " && sudo dscl . -create /Users/" username " NFSHomeDirectory " home
        " && { sudo createhomedir -c -u " username " >/dev/null 2>&1 || sudo mkdir -p " home "; }"
        " && sudo dscl . -append /Groups/com.apple.access_ssh GroupMembership " username
        " && sudo mkdir -p " home "/.ssh"
        " && sudo cp /Users/smithr/.ssh/authorized_keys " home "/.ssh/"
        " && sudo chown -R " username ":staff " home "/.ssh"
        " && sudo chmod 700 " home "/.ssh"
        " && sudo chmod 600 " home "/.ssh/authorized_keys"
        " && sudo bash -c \"printf 'eval \\$(/usr/libexec/path_helper -s)\\nexport LANG=en_US.UTF-8\\nexport LC_ALL=en_US.UTF-8\\n' > " home "/.bashrc\""
        " && sudo bash -c \"echo 'source ~/.bashrc' > " home "/.bash_profile\""
        " && sudo chown " username ":staff " home "/.bashrc " home "/.bash_profile"
        ;; Copy CoreSimulator RuntimeMap (SDK-to-runtime overrides)
        ;; Try user-level first, fall back to system-level
        " && { SMITHR_CORESIM=/Users/smithr/Library/Developer/CoreSimulator; "
        "SYS_CORESIM=/Library/Developer/CoreSimulator; "
        "RTMAP=\\\"\\\"; "
        "if [ -f \\\"$SMITHR_CORESIM/RuntimeMap.plist\\\" ]; then RTMAP=\\\"$SMITHR_CORESIM/RuntimeMap.plist\\\"; "
        "elif [ -f \\\"$SYS_CORESIM/RuntimeMap.plist\\\" ]; then RTMAP=\\\"$SYS_CORESIM/RuntimeMap.plist\\\"; "
        "fi; "
        "if [ -n \\\"$RTMAP\\\" ]; then "
          "sudo mkdir -p " home "/Library/Developer/CoreSimulator"
          " && sudo cp \\\"$RTMAP\\\" " home "/Library/Developer/CoreSimulator/"
          " && sudo chown -R " username ":staff " home "/Library; "
        "fi; }"
        " && echo " home "; "
      "fi")))

(defn user-exists?
  "Check if a macOS user exists on the VM."
  [resource username]
  (let [result (ssh-exec-raw! resource (str "dscl . -read /Users/" username " UniqueID 2>/dev/null"))]
    (= 0 (:exit result))))

(defn create-user!
  "Create a macOS user account for a build lease.
   Returns {:macos-user string :home-dir string} on success, nil on failure."
  [resource lease-id]
  (let [username (build-username lease-id)]
    (log/info "Creating macOS user" username "on" (:id resource))
    (let [result (ssh-exec-raw! resource (create-user-cmd username))]
      (if (= 0 (:exit result))
        (let [home-dir (str/trim (:out result))]
          (log/info "Created macOS user" username "on" (:id resource) "home:" home-dir)
          {:macos-user username :home-dir home-dir})
        (do
          (log/error "Failed to create macOS user" username
                     "on" (:id resource) ":" (:err result))
          nil)))))

(defn create-named-user!
  "Create a macOS user with a specific username (for workspaces).
   Returns {:macos-user string :home-dir string} on success, nil on failure."
  [resource username]
  (log/info "Creating macOS user" username "on" (:id resource))
  (let [result (ssh-exec-raw! resource (create-user-cmd username))]
    (if (= 0 (:exit result))
      (let [home-dir (str/trim (:out result))]
        (log/info "Created macOS user" username "on" (:id resource) "home:" home-dir)
        {:macos-user username :home-dir home-dir})
      (do
        (log/error "Failed to create macOS user" username
                   "on" (:id resource) ":" (:err result))
        nil))))

(defn ensure-user!
  "Ensure a macOS user exists and is properly set up (for workspace builds).
   Creates the user if it doesn't exist, repairs if partially created.
   Same contract as linux.clj ensure-user!."
  [resource username]
  (create-named-user! resource username))

(defn delete-user!
  "Delete a macOS user account and home directory.
   Handles process cleanup and both RAM disk and /Users paths.
   Times out after 30 seconds to prevent hanging on unresponsive VMs."
  [resource macos-user]
  (log/info "Deleting macOS user" macos-user "on" (:id resource))
  (let [home (str "/Users/" macos-user)
        ramdisk-home (str "/Volumes/BuildHomes/" macos-user)
        {:keys [ssh-host ssh-port]} (:connection resource)
        key-path (ssh-key-path)
        cmd ["ssh" "-i" key-path
             "-o" "StrictHostKeyChecking=no"
             "-o" "ConnectTimeout=10"
             "-p" (str (or ssh-port 10022))
             (str "smithr@" ssh-host)
             (str "sudo pkill -u " macos-user " 2>/dev/null; sleep 1; "
                  "sudo dscl . -delete /Users/" macos-user " 2>/dev/null; "
                  "sudo dscl . -delete /Groups/com.apple.access_ssh GroupMembership " macos-user " 2>/dev/null; "
                  "sudo rm -rf " home " " ramdisk-home " 2>/dev/null; "
                  "echo done")]
        proc (-> (ProcessBuilder. ^java.util.List cmd)
                 (.redirectErrorStream true)
                 (.start))
        finished? (.waitFor proc 30 java.util.concurrent.TimeUnit/SECONDS)]
    (if finished?
      (let [exit (.exitValue proc)]
        (when (not= 0 exit)
          (log/warn "Failed to fully clean up user" macos-user
                    "on" (:id resource) "exit:" exit))
        (= 0 exit))
      (do
        (log/warn "SSH user deletion timed out after 30s for" macos-user
                  "on" (:id resource) "- destroying process forcibly")
        (.destroyForcibly proc)
        false))))

(defn list-build-users
  "List existing build user accounts on a macOS VM."
  [resource]
  (let [result (ssh-exec-raw! resource
                 "dscl . -list /Users | grep -E '^build-|^artha-'")]
    (if (and (= 0 (:exit result))
             (not (str/blank? (:out result))))
      (str/split-lines (str/trim (:out result)))
      [])))
