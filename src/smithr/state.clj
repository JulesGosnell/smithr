;; Copyright 2026 Jules Gosnell
;; SPDX-License-Identifier: Apache-2.0

(ns smithr.state
  "State management for resources, leases, and hosts.
   All state mutations happen via swap! for atomicity.

   The `state` var is a MemoryStateStore that implements IDeref + IAtom,
   so existing @state and (swap! state f) calls work unchanged.
   See smithr.store for the protocol definitions."
  (:require [smithr.store.memory :as mem]))

(defonce state
  (mem/create-state-store
    {:resources  {}   ;; resource-id -> Resource
     :leases     {}   ;; lease-id -> Lease
     :hosts      {}   ;; host-label -> Host
     :workspaces {}   ;; workspace-name -> Workspace
     :adopts     {}   ;; adopt-id -> Adopt
     :events     []}  ;; vec of event maps, newest last
     ))

;; ---------------------------------------------------------------------------
;; Host operations
;; ---------------------------------------------------------------------------

(defn register-host!
  "Register a Docker host in the state."
  [label docker-uri]
  (swap! state assoc-in [:hosts label]
         {:label      label
          :docker-uri docker-uri
          :connected? true}))

(defn disconnect-host!
  "Mark a host as disconnected."
  [label]
  (swap! state assoc-in [:hosts label :connected?] false))

;; ---------------------------------------------------------------------------
;; Resource operations
;; ---------------------------------------------------------------------------

(defn upsert-resource!
  "Insert or update a resource in the state.
   Preserves existing worker-ssh-* connection data when new resource doesn't have it,
   since worker attachment happens separately from container inspection."
  [resource]
  (swap! state update-in [:resources (:id resource)]
         (fn [existing]
           (if (and existing
                    (get-in existing [:connection :worker-ssh-host])
                    (not (get-in resource [:connection :worker-ssh-host])))
             (update resource :connection merge
                     (select-keys (:connection existing)
                                  [:worker-ssh-host :worker-ssh-port]))
             resource))))

(defn remove-resource!
  "Remove a resource from the state."
  [resource-id]
  (swap! state update :resources dissoc resource-id))

(defn update-resource-status!
  "Update just the status of a resource."
  [resource-id status]
  (swap! state (fn [s]
                 (if (get-in s [:resources resource-id])
                   (-> s
                       (assoc-in [:resources resource-id :status] status)
                       (assoc-in [:resources resource-id :updated-at] (java.time.Instant/now)))
                   s))))

;; ---------------------------------------------------------------------------
;; Lease operations
;; ---------------------------------------------------------------------------

(defn add-lease!
  "Add a lease to the state. Returns the new state."
  [lease]
  (swap! state assoc-in [:leases (:id lease)] lease))

(defn remove-lease!
  "Remove a lease from the state."
  [lease-id]
  (swap! state update :leases dissoc lease-id))

;; ---------------------------------------------------------------------------
;; Queries (pure, no mutation)
;; ---------------------------------------------------------------------------

(defn resources
  "Get all resources, optionally filtered."
  ([] (vals (:resources @state)))
  ([pred] (filter pred (resources))))

(defn resource [id] (get-in @state [:resources id]))

(defn leases
  "Get all leases, optionally filtered."
  ([] (vals (:leases @state)))
  ([pred] (filter pred (leases))))

(defn lease [id] (get-in @state [:leases id]))

(defn hosts [] (vals (:hosts @state)))

(defn host [label] (get-in @state [:hosts label]))

(defn available-resources
  "Get warm resources matching type and platform."
  [resource-type platform]
  (resources (fn [r]
               (and (= (:status r) :warm)
                    (= (:type r) resource-type)
                    (= (:platform r) platform)))))

;; ---------------------------------------------------------------------------
;; Workspace operations
;; ---------------------------------------------------------------------------

(defn workspaces
  "Get all workspaces."
  ([] (vals (:workspaces @state)))
  ([pred] (filter pred (workspaces))))

(defn workspace [name] (get-in @state [:workspaces name]))

(defn invalidate-workspaces-for-resource!
  "Clear cached workspace entries that reference a given resource-id.
   Called when a resource restarts (die+start cycle) since workspace
   users on the VM no longer exist after a fresh boot."
  [resource-id]
  (swap! state
         (fn [s]
           (let [ws (:workspaces s)
                 stale (into [] (filter #(= (:resource-id (val %)) resource-id)) ws)]
             (if (seq stale)
               (do (reduce (fn [s' [k _]] (update s' :workspaces dissoc k)) s stale))
               s)))))

(defn available-for-build
  "Get resources available for a shared build lease.
   Returns resources that are :warm (no leases) or :shared with capacity remaining."
  ([platform]
   (available-for-build :vm platform))
  ([resource-type platform]
   (resources (fn [r]
                (and (= (:type r) (keyword resource-type))
                     (= (:platform r) (keyword platform))
                     (or (= (:status r) :warm)
                         (and (= (:status r) :shared)
                              (< (count (:active-leases r #{}))
                                 (:max-slots r 10)))))))))

;; ---------------------------------------------------------------------------
;; Adopt operations
;; ---------------------------------------------------------------------------

(defn add-adopt!
  "Add an adopt record to the state."
  [adopt]
  (swap! state assoc-in [:adopts (:id adopt)] adopt))

(defn remove-adopt!
  "Remove an adopt record from the state."
  [adopt-id]
  (swap! state update :adopts dissoc adopt-id))

(defn adopts
  "Get all adopts."
  ([] (vals (:adopts @state)))
  ([pred] (filter pred (adopts))))

(defn adopt [id] (get-in @state [:adopts id]))

(defn adopt-by-container-id
  "Find an adopt record by Docker container ID."
  [container-id]
  (first (filter #(= (:container-id %) container-id) (adopts))))

;; ---------------------------------------------------------------------------
;; Event log
;; ---------------------------------------------------------------------------

(def ^:private max-events 500)
(def ^:private max-age-hours 24)

(defn record-event!
  "Append an event to the audit log. Trims by count and age."
  [event-type data]
  (let [now    (java.time.Instant/now)
        cutoff (.minus now (java.time.Duration/ofHours max-age-hours))
        event  (assoc data
                 :type event-type
                 :timestamp (str now))]
    (swap! state update :events
           (fn [evts]
             (let [evts (conj evts event)
                   ;; Trim by age
                   evts (filterv #(pos? (compare (:timestamp %) (str cutoff))) evts)
                   ;; Trim by count
                   evts (if (> (count evts) max-events)
                          (vec (drop (- (count evts) max-events) evts))
                          evts)]
               evts)))))

(defn events
  "Get all events, optionally limited to last n."
  ([] (:events @state))
  ([n] (take-last n (:events @state))))

(defn available-for-phone
  "Get macOS VMs available for an exclusive phone lease.
   Only :warm VMs qualify (no existing leases of any type)."
  [platform]
  (resources (fn [r]
               (and (= (:type r) :vm)
                    (= (:platform r) (keyword platform))
                    (= (:status r) :warm)))))
