;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.store.hazelcast
  "Hazelcast-backed implementations of DistributedLock and SharedKV.

   StateStore remains in-memory (per-instance, volatile) — Hazelcast
   adds no value for local-only state. Use smithr.store.memory/create-state-store.

   This backend replaces NFS-based disk locks and KV with Hazelcast's
   distributed in-memory data grid:

   - DistributedLock: IMap with putIfAbsent/remove for atomic acquire/release.
     Analogous to the NFS mkdir pattern but distributed in-memory.
     NOTE: Not CP/Raft-based (FencedLock requires 3+ members for majority).
     For 2-node Smithr, IMap-based locks are equivalent to the current NFS
     approach — both have the same split-brain risk.

   - SharedKV: IMap for cross-instance key-value (templates, etc.).
     Values serialized as EDN strings for Clojure compatibility.

   Cluster discovery uses TCP/IP with explicit member addresses
   (more reliable than multicast for a known 2-node setup)."
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [smithr.store :as store]
            [smithr.store.memory :as mem])
  (:import [com.hazelcast.config Config JoinConfig TcpIpConfig]
           [com.hazelcast.core Hazelcast HazelcastInstance]
           [com.hazelcast.map IMap]))

;; ---------------------------------------------------------------------------
;; Hazelcast instance lifecycle
;; ---------------------------------------------------------------------------

(defn create-instance
  "Create an embedded Hazelcast instance with TCP/IP discovery.
   members is a seq of host addresses (e.g. [\"192.168.0.73\" \"192.168.0.74\"]).
   instance-name labels this node in cluster logs.
   bind-interface is an optional IP pattern to bind to (e.g. \"192.168.0.*\")."
  [instance-name members & {:keys [bind-interface]}]
  (let [config (doto (Config.)
                 (.setInstanceName instance-name)
                 (.setClusterName "smithr"))
        network-config (.getNetworkConfig config)
        join-config (.getJoin network-config)]
    ;; Bind to specific interface if provided (avoids loopback)
    (when bind-interface
      (doto (.getInterfaces network-config)
        (.setEnabled true)
        (.addInterface bind-interface)))
    ;; Disable multicast, use explicit TCP/IP members
    (-> join-config .getMulticastConfig (.setEnabled false))
    (let [tcp-config (doto (.getTcpIpConfig join-config)
                       (.setEnabled true))]
      (doseq [member members]
        (.addMember tcp-config member)))
    (log/info "Starting Hazelcast" instance-name "with members:" members
              (when bind-interface (str "bind:" bind-interface)))
    (Hazelcast/newHazelcastInstance config)))

(defn shutdown-instance
  "Shut down a Hazelcast instance gracefully."
  [^HazelcastInstance hz]
  (when hz
    (log/info "Shutting down Hazelcast" (.getName hz))
    (.shutdown hz)))

;; ---------------------------------------------------------------------------
;; HazelcastLock — IMap-based distributed lock
;; ---------------------------------------------------------------------------
;; Lock = entry in IMap "smithr-locks".
;; Acquire = putIfAbsent (atomic: returns nil if key didn't exist).
;; Release = remove.
;; Check  = containsKey.
;;
;; Values are EDN-serialized info maps (lessee, timestamp, etc.).

(deftype HazelcastLock [^IMap lock-map]
  store/DistributedLock
  (try-lock! [_ lock-id info]
    (let [info-str (pr-str (assoc info
                                  :lock-id lock-id
                                  :timestamp (str (java.time.Instant/now))))
          existing (.putIfAbsent lock-map (str lock-id) info-str)]
      (if (nil? existing)
        (do (log/info "Lock acquired:" lock-id
                      (when-let [l (:lessee info)] (str "by " l)))
            true)
        (do (log/debug "Lock busy:" lock-id)
            false))))

  (unlock! [_ lock-id]
    (when (.remove lock-map (str lock-id))
      (log/info "Lock released:" lock-id)))

  (locked? [_ lock-id]
    (.containsKey lock-map (str lock-id)))

  (cleanup-locks! [_ keep-pred]
    (doseq [key (.keySet lock-map)]
      (when-not (keep-pred key)
        (log/info "Cleaning stale lock:" key)
        (.remove lock-map key)))))

;; ---------------------------------------------------------------------------
;; HazelcastKV — IMap-based shared key-value store
;; ---------------------------------------------------------------------------
;; Values serialized as EDN strings. The :_files key (used by DiskKV for
;; companion files) is preserved in the EDN — no filesystem to write to.

(deftype HazelcastKV [^IMap kv-map]
  store/SharedKV
  (kv-get [_ key]
    (when-let [v (.get kv-map (str key))]
      (try
        (edn/read-string v)
        (catch Exception e
          (log/warn "Failed to deserialize KV entry" key ":" (.getMessage e))
          nil))))

  (kv-put! [_ key val]
    (.put kv-map (str key) (pr-str val))
    val)

  (kv-delete! [_ key]
    (.remove kv-map (str key))
    nil)

  (kv-list-keys [_]
    (seq (.keySet kv-map)))

  (kv-list-vals [_]
    (->> (.values kv-map)
         (keep (fn [v]
                 (try
                   (edn/read-string v)
                   (catch Exception e
                     (log/warn "Failed to deserialize KV value:" (.getMessage e))
                     nil)))))))

;; ---------------------------------------------------------------------------
;; Factory functions
;; ---------------------------------------------------------------------------

(defn create-lock
  "Create a Hazelcast-backed DistributedLock."
  [^HazelcastInstance hz]
  (HazelcastLock. (.getMap hz "smithr-locks")))

(defn create-kv
  "Create a Hazelcast-backed SharedKV store."
  [^HazelcastInstance hz]
  (HazelcastKV. (.getMap hz "smithr-kv")))

(defn create-store
  "Create a complete Store bundle with Hazelcast lock + KV and in-memory state.
   Returns {:hz instance, :store store-bundle} — caller must save hz for shutdown.

   members: seq of cluster member addresses
   instance-name: label for this node
   initial-state: initial value for the per-instance state atom"
  [instance-name members initial-state]
  (let [hz (create-instance instance-name members)
        state (mem/create-state-store initial-state)
        lock (create-lock hz)
        kv (create-kv hz)]
    {:hz hz
     :store (store/create-store state lock kv)}))
