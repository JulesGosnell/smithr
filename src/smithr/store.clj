;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.store
  "Protocols for Smithr's state management layer.

   Three independent concerns, each with pluggable backends:

   1. StateStore   — volatile per-instance state (resources, leases, hosts, etc.)
   2. DistributedLock — cross-instance exclusive locks (phone lease coordination)
   3. SharedKV     — cross-instance persistent key-value (templates, etc.)

   Backends:
     :memory  — all in-process (single instance, testing)
     :disk    — state in-process, locks+KV on NFS (current production)
     :hazelcast — distributed in-memory (future)
     :postgres   — full persistence (future)")

;; ---------------------------------------------------------------------------
;; StateStore — volatile per-instance state
;; ---------------------------------------------------------------------------
;; Mirrors the semantics of Clojure's atom: snapshot reads + atomic CAS.
;; The Memory backend implements IDeref and IAtom so existing code using
;; @state and (swap! state f) works unchanged during migration.

(defprotocol StateStore
  (deref-state [store]
    "Return the current state as an immutable Clojure map.
     Equivalent to @atom.")
  (swap-state! [store f]
    "Atomically transform the state. f: state-map -> state-map.
     Returns the new state. Equivalent to (swap! atom f).
     f must be pure — it may be retried on contention."))

;; ---------------------------------------------------------------------------
;; DistributedLock — cross-instance exclusive locks
;; ---------------------------------------------------------------------------
;; Used for phone lease coordination: only one Smithr instance can lease
;; a given phone resource across the cluster.

(defprotocol DistributedLock
  (try-lock! [lock lock-id info]
    "Attempt to acquire an exclusive lock identified by lock-id.
     info is a map of debugging metadata (lessee, lease-id, timestamp).
     Returns true if lock was acquired, false if already held.")
  (unlock! [lock lock-id]
    "Release an exclusive lock. No-op if not held.")
  (locked? [lock lock-id]
    "Check whether a lock is currently held.")
  (cleanup-locks! [lock keep-pred]
    "Remove locks where (keep-pred lock-id) returns false.
     Called on startup to clean up after crashes."))

;; ---------------------------------------------------------------------------
;; SharedKV — cross-instance persistent key-value
;; ---------------------------------------------------------------------------
;; Used for templates and other data that must be visible across all
;; Smithr instances and survive restarts.

(defprotocol SharedKV
  (kv-get [kv key]
    "Get a value by key. Returns nil if not found.")
  (kv-put! [kv key val]
    "Store a value. Returns the value.")
  (kv-delete! [kv key]
    "Delete a value by key.")
  (kv-list-keys [kv]
    "Return all keys.")
  (kv-list-vals [kv]
    "Return all values."))

;; ---------------------------------------------------------------------------
;; Store bundle — convenience container for all three concerns
;; ---------------------------------------------------------------------------

(defrecord Store [state lock kv])

(defn create-store
  "Create a Store bundle from individual implementations.
   Usage: (create-store my-state-store my-lock my-kv)"
  [state-store distributed-lock shared-kv]
  (->Store state-store distributed-lock shared-kv))
