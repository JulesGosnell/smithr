;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.store.memory
  "In-memory implementations of all store protocols.
   Suitable for single-instance deployments and testing.

   - StateStore:      Clojure atom (implements IDeref + IAtom for drop-in swap!/deref)
   - DistributedLock: atom of held lock IDs (in-process only)
   - SharedKV:        atom of key->value map (in-process only, volatile)"
  (:require [smithr.store :as store])
  (:import [clojure.lang IDeref IAtom]))

;; ---------------------------------------------------------------------------
;; MemoryStateStore — wraps an atom, implements IDeref + IAtom
;; ---------------------------------------------------------------------------
;; This lets existing code using @state/state and (swap! state/state f)
;; work unchanged — the MemoryStateStore IS the atom from the outside.

(deftype MemoryStateStore [^clojure.lang.Atom state-atom]
  store/StateStore
  (deref-state [_] (.deref state-atom))
  (swap-state! [_ f] (.swap state-atom f))

  IDeref
  (deref [_] (.deref state-atom))

  IAtom
  (swap [_ f] (.swap state-atom f))
  (swap [_ f arg] (.swap state-atom f arg))
  (swap [_ f arg1 arg2] (.swap state-atom f arg1 arg2))
  (swap [_ f x y args] (.swap state-atom f x y args))
  (reset [_ v] (.reset state-atom v))
  (compareAndSet [_ old new] (.compareAndSet state-atom old new)))

(defn create-state-store
  "Create an in-memory StateStore with the given initial state map."
  [init]
  (MemoryStateStore. (atom init)))

;; ---------------------------------------------------------------------------
;; MemoryLock — atom of #{lock-ids}
;; ---------------------------------------------------------------------------

(deftype MemoryLock [^clojure.lang.Atom locks]
  store/DistributedLock
  (try-lock! [_ lock-id _info]
    (loop []
      (let [old (.deref locks)]
        (if (contains? old lock-id)
          false
          (if (.compareAndSet locks old (conj old lock-id))
            true
            (recur))))))

  (unlock! [_ lock-id]
    (swap! locks disj lock-id)
    nil)

  (locked? [_ lock-id]
    (contains? (.deref locks) lock-id))

  (cleanup-locks! [_ keep-pred]
    (swap! locks (fn [s] (into #{} (filter keep-pred) s)))
    nil))

(defn create-lock
  "Create an in-memory DistributedLock."
  []
  (MemoryLock. (atom #{})))

;; ---------------------------------------------------------------------------
;; MemoryKV — atom of {key -> val}
;; ---------------------------------------------------------------------------

(deftype MemoryKV [^clojure.lang.Atom data]
  store/SharedKV
  (kv-get [_ key] (get (.deref data) key))
  (kv-put! [_ key val] (swap! data assoc key val) val)
  (kv-delete! [_ key] (swap! data dissoc key) nil)
  (kv-list-keys [_] (keys (.deref data)))
  (kv-list-vals [_] (vals (.deref data))))

(defn create-kv
  "Create an in-memory SharedKV store."
  ([] (MemoryKV. (atom {})))
  ([init] (MemoryKV. (atom init))))
