;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.store.disk
  "NFS-backed implementations of DistributedLock and SharedKV.
   StateStore is NOT provided — use smithr.store.memory/create-state-store
   since per-instance state is always volatile/in-memory.

   This backend is the current production configuration:
   - DistributedLock: atomic mkdir on shared NFS mount
   - SharedKV: files on shared NFS mount (directory per key)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [smithr.store :as store])
  (:import [java.io File]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; DiskLock — atomic mkdir on NFS for cross-host exclusive locks
;; ---------------------------------------------------------------------------
;; Lock = directory existence at <base-dir>/<sanitized-lock-id>/
;; Metadata written inside for debugging (lessee, lease-id, timestamp).
;; mkdir is atomic on POSIX — create-or-fail, no races.

(defn- sanitize-lock-id
  "Convert lock-id to a filesystem-safe directory name.
   e.g. 'megalodon:android:smithr-android-fe' -> 'megalodon--android--smithr-android-fe'"
  [lock-id]
  (clojure.string/replace (str lock-id) ":" "--"))

(defn- unsanitize-lock-id
  "Reverse of sanitize-lock-id."
  [dir-name]
  (clojure.string/replace dir-name "--" ":"))

(deftype DiskLock [^String base-dir]
  store/DistributedLock
  (try-lock! [_ lock-id info]
    (let [lock-dir (File. (str base-dir "/" (sanitize-lock-id lock-id)))]
      (if (.mkdir lock-dir)
        (do
          ;; Write debug info (best-effort)
          (try
            (spit (str (.getPath lock-dir) "/info")
                  (pr-str (assoc info :lock-id lock-id :timestamp (str (Instant/now)))))
            (catch Exception e
              (log/warn "Failed to write lock info:" (.getMessage e))))
          (log/info "Lock acquired:" lock-id
                    (when-let [l (:lessee info)] (str "by " l)))
          true)
        (do
          (log/debug "Lock busy:" lock-id)
          false))))

  (unlock! [_ lock-id]
    (let [lock-dir (File. (str base-dir "/" (sanitize-lock-id lock-id)))]
      (when (.exists lock-dir)
        (doseq [f (.listFiles lock-dir)]
          (.delete f))
        (.delete lock-dir)
        (log/info "Lock released:" lock-id))))

  (locked? [_ lock-id]
    (.isDirectory (File. (str base-dir "/" (sanitize-lock-id lock-id)))))

  (cleanup-locks! [_ keep-pred]
    (let [dir (File. base-dir)]
      (when (.exists dir)
        (doseq [lock-dir (.listFiles dir)]
          (when (.isDirectory lock-dir)
            (let [lock-id (unsanitize-lock-id (.getName lock-dir))]
              (when-not (keep-pred lock-id)
                (log/info "Cleaning stale lock:" lock-id)
                (doseq [f (.listFiles lock-dir)]
                  (.delete f))
                (.delete lock-dir)))))))))

(defn create-lock
  "Create an NFS-backed DistributedLock.
   base-dir is the shared directory (e.g. /srv/shared/smithr/leases)."
  [base-dir]
  (.mkdirs (File. base-dir))
  (DiskLock. base-dir))

;; ---------------------------------------------------------------------------
;; DiskKV — directory-per-key on NFS
;; ---------------------------------------------------------------------------
;; Each key is a directory: <base-dir>/<key>/
;; Value is stored as EDN in <key>/data.edn
;; Additional files can be stored alongside (e.g. compose.yml for templates).
;; The value map may include a :_files key with {filename -> content} for
;; extra files to write alongside data.edn.

(deftype DiskKV [^String base-dir]
  store/SharedKV
  (kv-get [_ key]
    (let [data-file (File. (str base-dir "/" key "/data.edn"))]
      (when (.exists data-file)
        (try
          (edn/read-string (slurp data-file))
          (catch Exception e
            (log/warn "Failed to read KV entry" key ":" (.getMessage e))
            nil)))))

  (kv-put! [_ key val]
    (let [dir (str base-dir "/" key)]
      (.mkdirs (File. dir))
      ;; Write the value as EDN (excluding _files)
      (let [data (dissoc val :_files)]
        (spit (str dir "/data.edn") (pr-str data)))
      ;; Write any companion files
      (doseq [[filename content] (:_files val)]
        (spit (str dir "/" filename) content))
      val))

  (kv-delete! [_ key]
    (let [dir (File. (str base-dir "/" key))]
      (when (.exists dir)
        (doseq [f (.listFiles dir)]
          (.delete f))
        (.delete dir))))

  (kv-list-keys [_]
    (let [dir (File. base-dir)]
      (when (.isDirectory dir)
        (->> (.listFiles dir)
             (filter #(.isDirectory %))
             (filter #(.exists (File. % "data.edn")))
             (map #(.getName %))))))

  (kv-list-vals [_]
    (let [dir (File. base-dir)]
      (when (.isDirectory dir)
        (->> (.listFiles dir)
             (filter #(.isDirectory %))
             (keep (fn [d]
                     (let [data-file (File. d "data.edn")]
                       (when (.exists data-file)
                         (try
                           (edn/read-string (slurp data-file))
                           (catch Exception e
                             (log/warn "Failed to read KV entry" (.getName d)
                                       ":" (.getMessage e))
                             nil)))))))))))

(defn create-kv
  "Create an NFS-backed SharedKV store.
   base-dir is the shared directory (e.g. /srv/shared/smithr/templates)."
  [base-dir]
  (.mkdirs (File. base-dir))
  (DiskKV. base-dir))
