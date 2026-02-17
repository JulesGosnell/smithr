(ns hammar.state
  "Atom-based state management for resources, leases, and hosts.
   All state mutations happen via swap! for atomicity.")

(defonce state
  (atom {:resources {}   ;; resource-id -> Resource
         :leases    {}   ;; lease-id -> Lease
         :hosts     {}}  ;; host-label -> Host
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
  "Insert or update a resource in the state."
  [resource]
  (swap! state assoc-in [:resources (:id resource)] resource))

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

(defn available-for-build
  "Get macOS VMs available for a shared build lease.
   Returns VMs that are :warm (no leases) or :shared with capacity remaining."
  [platform]
  (resources (fn [r]
               (and (= (:type r) :vm)
                    (= (:platform r) (keyword platform))
                    (or (= (:status r) :warm)
                        (and (= (:status r) :shared)
                             (< (count (:active-leases r #{}))
                                (:max-slots r 10))))))))

(defn available-for-phone
  "Get macOS VMs available for an exclusive phone lease.
   Only :warm VMs qualify (no existing leases of any type)."
  [platform]
  (resources (fn [r]
               (and (= (:type r) :vm)
                    (= (:platform r) (keyword platform))
                    (= (:status r) :warm)))))
