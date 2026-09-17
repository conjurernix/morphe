(ns morphe.world)

(defn world
  "Creates an empty immutable world."
  []
  {:entities #{}, :next-entity-id 0, :components {}, :resources {}})

(defn entities [world] (:entities world))
(defn next-entity-id [world] (:next-entity-id world))
(defn component-store [world component-key]
  (get-in world [:components component-key] {}))
(defn with-component-store [world component-key store]
  (if (seq store)
    (assoc-in world [:components component-key] store)
    (update world :components dissoc component-key)))
