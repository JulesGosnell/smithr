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

(defn user-exists?
  "Check if a macOS user exists on the VM."
  [resource username]
  (let [result (ssh-exec! resource (str "dscl . -read /Users/" username " UniqueID 2>/dev/null"))]
    (= 0 (:exit result))))

(defn- create-macos-user!
  "Create a macOS user account via the create-build-user.sh script.
   The script handles dscl, SSH keys, access group, profile, and
   automatically uses the RAM disk at /Volumes/BuildHomes when available.
   Returns {:macos-user string :home-dir string} on success, nil on failure."
  [resource username real-name]
  (log/info "Creating macOS user" username "on" (:id resource))
  (let [result (ssh-exec! resource
                          (str "/Users/smithr/bin/create-build-user.sh " username))]
    (if (= 0 (:exit result))
      (let [home-dir (str/trim (:out result))]
        (log/info "Created macOS user" username "on" (:id resource)
                  "home:" home-dir)
        {:macos-user username :home-dir home-dir})
      (do
        (log/error "Failed to create macOS user" username
                   "on" (:id resource) ":" (:err result))
        nil))))

(defn create-user!
  "Create a macOS user account for a build lease.
   Returns {:macos-user string :home-dir string} on success, nil on failure."
  [resource lease-id]
  (let [username (build-username lease-id)]
    (create-macos-user! resource username (str "Build " (subs lease-id 0 (min 8 (count lease-id)))))))

(defn create-named-user!
  "Create a macOS user with a specific username (for workspaces).
   Returns {:macos-user string :home-dir string} on success, nil on failure."
  [resource username]
  (create-macos-user! resource username (str "Workspace " username)))

(defn ensure-user!
  "Ensure a macOS user exists (for warm/workspace builds).
   Creates the user if it doesn't exist, returns info either way.
   Queries the actual home directory (may be on RAM disk)."
  [resource username]
  (if (user-exists? resource username)
    (let [result (ssh-exec! resource
                            (str "dscl . -read /Users/" username
                                 " NFSHomeDirectory | awk '{print $2}'"))
          home-dir (if (= 0 (:exit result))
                     (str/trim (:out result))
                     (str "/Users/" username))]
      (log/info "Workspace user" username "already exists on" (:id resource)
                "home:" home-dir)
      {:macos-user username :home-dir home-dir})
    (create-named-user! resource username)))

(defn delete-user!
  "Delete a macOS user account and home directory via delete-build-user.sh.
   The script handles process cleanup and both RAM disk and /Users paths."
  [resource macos-user]
  (log/info "Deleting macOS user" macos-user "on" (:id resource))
  (let [result (ssh-exec! resource
                          (str "/Users/smithr/bin/delete-build-user.sh " macos-user))]
    (when (not= 0 (:exit result))
      (log/warn "Failed to fully clean up user" macos-user
                "on" (:id resource) ":" (:err result)))
    (= 0 (:exit result))))

(defn list-build-users
  "List existing build user accounts on a macOS VM via list-build-users.sh."
  [resource]
  (let [result (ssh-exec! resource "/Users/smithr/bin/list-build-users.sh")]
    (if (and (= 0 (:exit result))
             (not (str/blank? (:out result))))
      (str/split-lines (str/trim (:out result)))
      [])))
