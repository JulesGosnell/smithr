(ns smithr.ssh
  "Shared SSH utilities: key resolution and common SSH operations."
  (:require [clojure.tools.logging :as log]
            [smithr.config :as config]))

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
                 (.setReadable f false false)
                 (.setReadable f true true)
                 (.setWritable f false false)
                 (.setWritable f true true)
                 (.setExecutable f false false))
               (catch Exception _ nil))
          (log/info "Resolved SSH key path:" abs)
          abs)
        (do
          (log/warn "No SSH key found, tried:" (pr-str candidates))
          (first candidates))))))

(defn ssh-key-path
  "Return the resolved SSH key path for tunnel/remote connections."
  []
  @cached-ssh-key-path)
