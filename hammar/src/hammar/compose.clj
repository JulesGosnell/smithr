(ns hammar.compose
  "Shell out to docker compose CLI for container lifecycle operations."
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader InputStreamReader]))

(defn- run-compose
  "Run a docker compose command, return {:exit int :out string :err string}."
  [& args]
  (let [cmd     (into ["docker" "compose"] args)
        _       (log/debug "Running:" (pr-str cmd))
        process (-> (ProcessBuilder. ^java.util.List cmd)
                    (.redirectErrorStream false)
                    (.start))
        out     (slurp (.getInputStream process))
        err     (slurp (.getErrorStream process))
        exit    (.waitFor process)]
    (when (pos? exit)
      (log/warn "docker compose exited" exit ":" err))
    {:exit exit :out out :err err}))

(defn compose-up
  "Start services using compose files."
  [project-name compose-files & {:keys [detach] :or {detach true}}]
  (apply run-compose
         (concat ["-p" project-name]
                 (mapcat (fn [f] ["-f" f]) compose-files)
                 ["up"]
                 (when detach ["-d"]))))

(defn compose-down
  "Stop and remove services."
  [project-name compose-files]
  (apply run-compose
         (concat ["-p" project-name]
                 (mapcat (fn [f] ["-f" f]) compose-files)
                 ["down"])))

(defn compose-ps
  "List containers for a project."
  [project-name compose-files]
  (apply run-compose
         (concat ["-p" project-name]
                 (mapcat (fn [f] ["-f" f]) compose-files)
                 ["ps" "--format" "json"])))
