(ns hammar.macos
  "macOS user lifecycle management via SSH.
   Creates and deletes per-build user accounts on macOS VMs
   for isolated concurrent build access."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn build-username
  "Generate a macOS username for a build lease.
   Uses first 8 characters of the lease ID for uniqueness."
  [lease-id]
  (str "build-" (subs lease-id 0 (min 8 (count lease-id)))))

(defn- ssh-exec!
  "Execute a command on a macOS VM via SSH.
   Returns {:exit int :out string :err string}."
  [resource cmd]
  (let [{:keys [ssh-host ssh-port]} (:connection resource)
        result (sh "ssh"
                   "-i" "/ssh-key/macos-ssh-key"
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

(defn- next-uid
  "Find the next available UID for build users (starting at 600)."
  [resource]
  (let [result (ssh-exec! resource "dscl . -list /Users UniqueID | grep '^build-' | awk '{print $2}' | sort -n | tail -1")]
    (if (and (= 0 (:exit result))
             (not (str/blank? (:out result))))
      (inc (Integer/parseInt (str/trim (:out result))))
      600)))

(defn create-user!
  "Create a macOS user account for a build lease.
   Returns {:macos-user string :home-dir string} on success, nil on failure."
  [resource lease-id]
  (let [username (build-username lease-id)
        home-dir (str "/Users/" username)
        uid (next-uid resource)]
    (log/info "Creating macOS user" username "uid:" uid "on" (:id resource))
    (let [cmds [(str "sudo dscl . -create /Users/" username)
                (str "sudo dscl . -create /Users/" username " UserShell /bin/bash")
                (str "sudo dscl . -create /Users/" username " RealName 'Build " (subs lease-id 0 (min 8 (count lease-id))) "'")
                (str "sudo dscl . -create /Users/" username " UniqueID " uid)
                (str "sudo dscl . -create /Users/" username " PrimaryGroupID 20")
                (str "sudo dscl . -create /Users/" username " NFSHomeDirectory " home-dir)
                (str "sudo createhomedir -c -u " username)
                ;; Add to SSH access group (required by macOS)
                (str "sudo dscl . -append /Groups/com.apple.access_ssh GroupMembership " username)
                ;; Set up SSH key auth — copy from smithr admin user
                (str "sudo mkdir -p " home-dir "/.ssh")
                (str "sudo cp /Users/smithr/.ssh/authorized_keys " home-dir "/.ssh/")
                (str "sudo chown -R " username ":staff " home-dir "/.ssh")
                (str "sudo chmod 700 " home-dir "/.ssh")
                (str "sudo chmod 600 " home-dir "/.ssh/authorized_keys")
                ;; Set up shell profile — PATH and locale for SSH sessions
                (str "sudo bash -c 'printf \"eval \\$(/usr/libexec/path_helper -s)\\nexport LANG=en_US.UTF-8\\nexport LC_ALL=en_US.UTF-8\\n\" > " home-dir "/.bashrc'")
                (str "sudo bash -c 'echo \"source ~/.bashrc\" > " home-dir "/.bash_profile'")
                (str "sudo chown " username ":staff " home-dir "/.bashrc " home-dir "/.bash_profile")]
          combined (str/join " && " cmds)
          result (ssh-exec! resource combined)]
      (if (= 0 (:exit result))
        (do
          (log/info "Created macOS user" username "on" (:id resource))
          {:macos-user username :home-dir home-dir})
        (do
          (log/error "Failed to create macOS user" username
                     "on" (:id resource) ":" (:err result))
          nil)))))

(defn delete-user!
  "Delete a macOS user account and home directory."
  [resource macos-user]
  (log/info "Deleting macOS user" macos-user "on" (:id resource))
  (let [result (ssh-exec! resource
                          (str "sudo dscl . -delete /Users/" macos-user
                               " && sudo rm -rf /Users/" macos-user))]
    (when (not= 0 (:exit result))
      (log/warn "Failed to fully clean up user" macos-user
                "on" (:id resource) ":" (:err result)))
    (= 0 (:exit result))))

(defn list-build-users
  "List existing build user accounts on a macOS VM."
  [resource]
  (let [result (ssh-exec! resource "ls /Users/ | grep '^build-'")]
    (if (= 0 (:exit result))
      (str/split-lines (str/trim (:out result)))
      [])))
