(ns morphe.component
  (:require [morphe.entity :as entity]
            [morphe.world :as world]))

(defn component [world entity-id component-key]
  (get (world/component-store world component-key) entity-id))

(defn has-component? [world entity-id component-key]
  (contains? (world/component-store world component-key) entity-id))

(defn- require-entity [world entity-id]
  (when-not (entity/alive? world entity-id)
    (throw (ex-info "Cannot modify a component on a dead entity" {:entity entity-id}))))

(defn add-component [world entity-id component-key value]
  (require-entity world entity-id)
  (when (has-component? world entity-id component-key)
    (throw (ex-info "Entity already has this component"
                    {:entity entity-id :component component-key})))
  (assoc-in world [:components component-key entity-id] value))

(defn set-component [world entity-id component-key value]
  (require-entity world entity-id)
  (assoc-in world [:components component-key entity-id] value))

(defn remove-component [world entity-id component-key]
  (require-entity world entity-id)
  (world/with-component-store world component-key
                               (dissoc (world/component-store world component-key) entity-id)))

(defn update-component [world entity-id component-key f & args]
  (when-not (has-component? world entity-id component-key)
    (throw (ex-info "Cannot update a missing component"
                    {:entity entity-id :component component-key})))
  (set-component world entity-id component-key
                 (apply f (component world entity-id component-key) args)))
