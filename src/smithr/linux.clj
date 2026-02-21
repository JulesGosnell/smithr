(ns smithr.linux
  "Linux user lifecycle management via SSH.
   Creates and deletes per-build user accounts on Linux containers
   for isolated concurrent build access.
   Same interface as smithr.macos but uses useradd/userdel."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [smithr.config :as config]))

(defn build-username
  "Generate a Linux username for a build lease.
   Uses first 8 characters of the lease ID for uniqueness."
  [lease-id]
  (str "build-" (subs lease-id 0 (min 8 (count lease-id)))))

(defonce ^:private cached-ssh-key-path
  (delay
    (let [configured (get-in (config/load-config) [:tunnel :key-path])
          candidates (remove nil?
                       [configured
                        "layers/scripts/ios/ssh/macos-ssh-key"
                        "../layers/scripts/ios/ssh/macos-ssh-key"
                        "/ssh-key/macos-ssh-key"
                        "/srv/shared/images/ssh/macos-ssh-key"])
          found (first (filter #(.exists (java.io.File. %)) candidates))]
      (if found
        (let [abs (.getCanonicalPath (java.io.File. found))]
          ;; SSH requires private keys to be 0600
          (try (let [f (java.io.File. abs)]
                 (.setReadable f false false)    ;; remove read for all
                 (.setReadable f true true)      ;; add read for owner only
                 (.setWritable f false false)    ;; remove write for all
                 (.setWritable f true true)      ;; add write for owner only
                 (.setExecutable f false false)) ;; remove execute for all
               (catch Exception _ nil))
          (log/info "Linux SSH key resolved:" abs)
          abs)
        (do
          (log/warn "No SSH key found for Linux, tried:" (pr-str candidates))
          (first candidates))))))

(defn- ssh-key-path [] @cached-ssh-key-path)

(defn tunnel-ssh-key
  "Return the SSH key path for tunnel connections to Linux containers.
   Used by lease.clj when SSH-ing directly to a build container."
  []
  (ssh-key-path))

(defn ssh-exec-raw!
  "Execute a command on a Linux container via SSH.
   Returns {:exit int :out string :err string}."
  [resource cmd]
  (let [{:keys [ssh-host ssh-port]} (:connection resource)
        key-path (ssh-key-path)
        result (sh "ssh"
                   "-i" key-path
                   "-o" "StrictHostKeyChecking=no"
                   "-o" "ConnectTimeout=10"
                   "-p" (str (or ssh-port 22))
                   (str "smithr@" ssh-host)
                   cmd)]
    (when (not= 0 (:exit result))
      (log/warn "SSH command failed on" (:id resource)
                "exit:" (:exit result)
                "err:" (str/trim (:err result))))
    result))

(defn user-exists?
  "Check if a Linux user exists on the container."
  [resource username]
  (let [result (ssh-exec-raw! resource (str "id " username " 2>/dev/null"))]
    (= 0 (:exit result))))

(defn create-user!
  "Create a Linux user account for a build lease.
   Returns {:macos-user string :home-dir string} on success, nil on failure.
   Key name is :macos-user for compatibility with existing lease code."
  [resource lease-id]
  (let [username (build-username lease-id)]
    (log/info "Creating Linux user" username "on" (:id resource))
    (let [result (ssh-exec-raw! resource
                   (str "sudo useradd -m -s /bin/bash " username
                        " && sudo usermod -aG docker " username " 2>/dev/null || true"
                        " && sudo mkdir -p /home/" username "/.ssh"
                        " && sudo cp /home/smithr/.ssh/authorized_keys /home/" username "/.ssh/authorized_keys"
                        " && sudo chown -R " username ":" username " /home/" username "/.ssh"
                        " && sudo chmod 700 /home/" username "/.ssh"
                        " && sudo chmod 600 /home/" username "/.ssh/authorized_keys"
                        " && echo /home/" username))]
      (if (= 0 (:exit result))
        (let [home-dir (str/trim (:out result))]
          (log/info "Created Linux user" username "on" (:id resource) "home:" home-dir)
          {:macos-user username :home-dir home-dir})
        (do
          (log/error "Failed to create Linux user" username
                     "on" (:id resource) ":" (:err result))
          nil)))))

(defn ensure-user!
  "Ensure a Linux user exists (for warm/workspace builds).
   Creates the user if it doesn't exist, returns info either way."
  [resource username]
  (if (user-exists? resource username)
    (do
      (log/info "Workspace user" username "already exists on" (:id resource))
      {:macos-user username :home-dir (str "/home/" username)})
    (do
      (log/info "Creating workspace user" username "on" (:id resource))
      (let [result (ssh-exec-raw! resource
                     (str "sudo useradd -m -s /bin/bash " username
                          " && sudo usermod -aG docker " username " 2>/dev/null || true"
                          " && sudo mkdir -p /home/" username "/.ssh"
                          " && sudo cp /home/smithr/.ssh/authorized_keys /home/" username "/.ssh/authorized_keys"
                          " && sudo chown -R " username ":" username " /home/" username "/.ssh"
                          " && sudo chmod 700 /home/" username "/.ssh"
                          " && sudo chmod 600 /home/" username "/.ssh/authorized_keys"
                          " && echo /home/" username))]
        (if (= 0 (:exit result))
          (let [home-dir (str/trim (:out result))]
            (log/info "Created workspace user" username "on" (:id resource) "home:" home-dir)
            {:macos-user username :home-dir home-dir})
          (do
            (log/error "Failed to create workspace user" username
                       "on" (:id resource) ":" (:err result))
            nil))))))

(defn delete-user!
  "Delete a Linux user account and home directory."
  [resource username]
  (log/info "Deleting Linux user" username "on" (:id resource))
  (let [result (ssh-exec-raw! resource
                 (str "sudo pkill -u " username " 2>/dev/null; sleep 1; sudo userdel -r " username " 2>/dev/null; echo done"))]
    (when (not= 0 (:exit result))
      (log/warn "Failed to fully clean up user" username
                "on" (:id resource) ":" (:err result)))
    (= 0 (:exit result))))
