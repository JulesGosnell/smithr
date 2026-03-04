;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.templates
  "Dynamic template management — publish, serve, and provision from
   user-uploaded compose templates.

   Templates are stored on shared disk (/srv/shared/smithr/templates/)
   so both Smithr instances see them. Each template is a directory:
     <name>/
       meta.edn   — {:ports [3000], :type \"server\", :platform \"artha\"}
       compose.yml — the service compose (for provisioning)

   The proxy compose YAML is auto-generated from metadata."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private templates-dir "/srv/shared/smithr/templates")

;; In-memory cache, refreshed from disk
(defonce ^:private templates (atom {}))

;; ---------------------------------------------------------------------------
;; Disk I/O
;; ---------------------------------------------------------------------------

(defn- template-path [name]
  (str templates-dir "/" name))

(defn- load-template-from-disk
  "Load a single template directory. Returns nil if invalid."
  [name]
  (let [dir (template-path name)
        meta-file (io/file dir "meta.edn")
        compose-file (io/file dir "compose.yml")]
    (when (and (.exists meta-file) (.exists compose-file))
      (try
        (let [meta (edn/read-string (slurp meta-file))
              compose (slurp compose-file)]
          (assoc meta :name name :compose compose))
        (catch Exception e
          (log/warn "Failed to load template" name ":" (.getMessage e))
          nil)))))

(defn load-templates!
  "Scan templates-dir and load all templates into the atom."
  []
  (let [dir (io/file templates-dir)]
    (when (.isDirectory dir)
      (let [loaded (->> (.listFiles dir)
                        (filter #(.isDirectory %))
                        (map #(.getName %))
                        (keep (fn [n] (when-let [t (load-template-from-disk n)] [n t])))
                        (into {}))]
        (reset! templates loaded)
        (log/info "Loaded" (count loaded) "dynamic templates:"
                  (str/join ", " (keys loaded)))))))

(defn save-template!
  "Write a template to disk and update the in-memory cache.
   meta should contain :ports, :type, :platform.
   compose is the raw YAML string."
  [name meta compose]
  (let [dir (template-path name)]
    (.mkdirs (io/file dir))
    (spit (str dir "/meta.edn") (pr-str (dissoc meta :name :compose)))
    (spit (str dir "/compose.yml") compose)
    (let [full (assoc meta :name name :compose compose)]
      (swap! templates assoc name full)
      (log/info "Published template:" name "type:" (:type meta) "platform:" (:platform meta))
      full)))

(defn delete-template!
  "Remove a template from disk and cache."
  [name]
  (let [dir (io/file (template-path name))]
    (when (.exists dir)
      (doseq [f (.listFiles dir)] (.delete f))
      (.delete dir))
    (swap! templates dissoc name)
    (log/info "Deleted template:" name)))

;; ---------------------------------------------------------------------------
;; Lookup
;; ---------------------------------------------------------------------------

(defn get-template
  "Look up a dynamic template by name."
  [name]
  (get @templates name))

(defn list-templates
  "Return all dynamic templates (without compose YAML for listing)."
  []
  (mapv (fn [[_ t]] (dissoc t :compose)) @templates))

(defn template-names
  "Return the set of dynamic template names."
  []
  (set (keys @templates)))

;; ---------------------------------------------------------------------------
;; Proxy compose generation
;; ---------------------------------------------------------------------------

(defn generate-proxy-compose
  "Generate a proxy sidecar compose YAML for a dynamic template.
   The proxy leases the resource and tunnels its port(s)."
  [name registry smithr-url]
  (let [tmpl (get-template name)]
    (when tmpl
      (let [ports (:ports tmpl)
            ports-str (str/join "," (map str ports))
            service-name (str/replace name #"[^a-zA-Z0-9-]" "-")]
        (str "services:\n"
             "  " service-name ":\n"
             "    image: " registry "/smithr/proxy:latest\n"
             "    pull_policy: always\n"
             "    stop_grace_period: 120s\n"
             "    healthcheck:\n"
             "      test: /usr/local/bin/healthcheck.sh\n"
             "      interval: 5s\n"
             "      timeout: 5s\n"
             "      retries: 60\n"
             "      start_period: 10s\n"
             "    environment:\n"
             "      SMITHR_MODE: lease\n"
             "      SMITHR_URL: " smithr-url "\n"
             "      SMITHR_RESOURCE_TYPE: " (:type tmpl) "\n"
             "      SMITHR_PLATFORM: " (:platform tmpl) "\n"
             "      SMITHR_LESSEE: ${SMITHR_LESSEE:-anonymous}\n"
             "      SMITHR_PORTS: \"" ports-str "\"\n"
             "      SMITHR_TTL: ${SMITHR_TTL:-3600}\n"
             "    networks:\n"
             "      - smithr-network\n"
             "\n"
             "networks:\n"
             "  smithr-network:\n"
             "    external: true\n")))))
