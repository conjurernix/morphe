(ns morphe.entity
  (:require [morphe.world :as world]))

(defn alive? [world entity]
  (contains? (world/entities world) entity))

(defn spawn
  ([world] (spawn world {}))
  ([world initial-components]
   (let [entity (world/next-entity-id world)
         world' (-> world
                    (update :entities conj entity)
                    (update :next-entity-id inc))]
     [(reduce-kv
        (fn [current-world component-key value]
          (assoc-in current-world [:components component-key entity] value))
        world'
        initial-components)
      entity])))

(defn destroy [world entity]
  (if-not (alive? world entity)
    world
    (reduce-kv
      (fn [current-world component-key store]
        (world/with-component-store current-world component-key (dissoc store entity)))
      (update world :entities disj entity)
      (:components world))))
