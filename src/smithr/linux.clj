;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.linux
  "Linux user lifecycle management via SSH.
   Creates and deletes per-build user accounts on Linux containers
   for isolated concurrent build access.
   Same interface as smithr.macos but uses useradd/userdel."
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [smithr.ssh :as ssh]))

(defn build-username
  "Generate a Linux username for a build lease.
   Uses first 8 characters of the lease ID for uniqueness."
  [lease-id]
  (str "build-" (subs lease-id 0 (min 8 (count lease-id)))))

(defn- ssh-key-path [] (ssh/ssh-key-path))

(defn tunnel-ssh-key
  "Return the SSH key path for tunnel connections to Linux containers.
   Used by lease.clj when SSH-ing directly to a build container."
  []
  (ssh/ssh-key-path))

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
  "Delete a Linux user account and home directory.
   Times out after 30 seconds to prevent hanging on unresponsive containers."
  [resource username]
  (log/info "Deleting Linux user" username "on" (:id resource))
  (let [{:keys [ssh-host ssh-port]} (:connection resource)
        key-path (ssh-key-path)
        cmd ["ssh" "-i" key-path
             "-o" "StrictHostKeyChecking=no"
             "-o" "ConnectTimeout=10"
             "-p" (str (or ssh-port 22))
             (str "smithr@" ssh-host)
             (str "sudo pkill -u " username " 2>/dev/null; sleep 1; sudo userdel -r " username " 2>/dev/null; echo done")]
        proc (-> (ProcessBuilder. ^java.util.List cmd)
                 (.redirectErrorStream true)
                 (.start))
        finished? (.waitFor proc 30 java.util.concurrent.TimeUnit/SECONDS)]
    (if finished?
      (let [exit (.exitValue proc)]
        (when (not= 0 exit)
          (log/warn "Failed to fully clean up user" username
                    "on" (:id resource) "exit:" exit))
        (= 0 exit))
      (do
        (log/warn "SSH user deletion timed out after 30s for" username
                  "on" (:id resource) "- destroying process forcibly")
        (.destroyForcibly proc)
        false))))
