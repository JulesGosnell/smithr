;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.templates
  "Dynamic template management — publish, serve, and provision from
   user-uploaded compose templates.

   Storage is pluggable via the smithr.store/SharedKV protocol.
   Default: DiskKV (NFS) for cross-host visibility.
   Memory: MemoryKV for single-instance / testing.

   The proxy compose YAML is auto-generated from metadata."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [smithr.store :as store]))

(defonce ^:private kv (atom nil))

;; Legacy disk path — used only for one-time migration from old format
(def ^:private legacy-templates-dir "/srv/shared/smithr/templates")

(defn init-kv!
  "Initialize the SharedKV backend for templates. Called once at startup."
  [shared-kv]
  (reset! kv shared-kv))

;; ---------------------------------------------------------------------------
;; Migration from legacy disk format (meta.edn + compose.yml → data.edn)
;; ---------------------------------------------------------------------------

(defn- migrate-legacy-templates!
  "One-time migration: if old-format templates exist (meta.edn + compose.yml)
   but data.edn does not, write the combined value via the KV store."
  []
  (let [dir (io/file legacy-templates-dir)]
    (when (.isDirectory dir)
      (doseq [d (.listFiles dir)]
        (when (.isDirectory d)
          (let [name (.getName d)
                meta-file (io/file d "meta.edn")
                compose-file (io/file d "compose.yml")
                data-file (io/file d "data.edn")]
            (when (and (.exists meta-file) (.exists compose-file)
                       (not (.exists data-file)))
              (try
                (let [meta (edn/read-string (slurp meta-file))
                      compose (slurp compose-file)]
                  (store/kv-put! @kv name (assoc meta :name name :compose compose))
                  (log/info "Migrated legacy template:" name))
                (catch Exception e
                  (log/warn "Failed to migrate template" name ":" (.getMessage e)))))))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn load-templates!
  "Load templates into the KV store. Migrates old-format templates on first run."
  []
  (when @kv
    (migrate-legacy-templates!)
    (let [names (store/kv-list-keys @kv)]
      (log/info "Loaded" (count names) "dynamic templates:"
                (str/join ", " names)))))

(defn save-template!
  "Publish a template. meta should contain :ports, :type, :platform.
   compose is the raw YAML string."
  [name meta compose]
  (let [full (assoc meta :name name :compose compose)]
    (store/kv-put! @kv name full)
    (log/info "Published template:" name "type:" (:type meta) "platform:" (:platform meta))
    full))

(defn delete-template!
  "Remove a template."
  [name]
  (store/kv-delete! @kv name)
  (log/info "Deleted template:" name))

(defn get-template
  "Look up a dynamic template by name."
  [name]
  (store/kv-get @kv name))

(defn list-templates
  "Return all dynamic templates (without compose YAML for listing)."
  []
  (mapv #(dissoc % :compose) (store/kv-list-vals @kv)))

(defn template-names
  "Return the set of dynamic template names."
  []
  (set (store/kv-list-keys @kv)))

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
