(ns morphe.core
  (:refer-clojure :exclude [send])
  (:require [morphe.internal.entity :as entity]
            [morphe.internal.event :as event]
            [morphe.internal.runtime :as runtime]))

(defn game
  "Creates a game with an optional global context map."
  ([] (runtime/game))
  ([context] (runtime/game context)))

(defn entity
  "Creates an entity from state, ordered components, an update handler, and an optional render handler."
  ([state components handler]
   (entity/entity state components handler))
  ([state components handler render-handler]
   (entity/entity state components handler render-handler)))

(defn typed-entity
  "Creates a restorable entity identified by a stable type."
  ([type state components handler]
   (entity/typed-entity type state components handler))
  ([type state components handler render-handler]
   (entity/typed-entity type state components handler render-handler)))

(defn component
  "Creates a component that owns state under its namespace-qualified key."
  [key handler]
  (entity/component key handler))

(defn add-entity
  "Adds an entity under a unique, non-nil ID and preserves insertion order."
  [game entity-id entity-value]
  (runtime/add-entity game entity-id entity-value))

(defn remove-entity
  "Removes an existing entity while preserving the order of all remaining entities."
  [game entity-id]
  (runtime/remove-entity game entity-id))

(defn replace-entity
  "Replaces an existing entity without changing its insertion position."
  [game entity-id entity-value]
  (runtime/replace-entity game entity-id entity-value))

(defn update-context
  "Updates the immutable game context with f and optional arguments."
  [game f & args]
  (apply runtime/update-context game f args))

(defn replace-scene
  "Creates replacement game state from context and ordered [entity-id entity] pairs."
  ([context entity-entries]
   (runtime/replace-scene context entity-entries))
  ([game context entity-entries]
   (runtime/replace-scene game context entity-entries)))

(defn get-entity
  "Returns the entity registered under entity-id, or nil when absent."
  [game entity-id]
  (runtime/get-entity game entity-id))

(defn entity-state
  "Returns an entity's local state map."
  [entity-value]
  (entity/state entity-value))

(defn entity-type
  "Returns a typed entity's stable type, or nil for an untyped entity."
  [entity-value]
  (entity/entity-type entity-value))

(defn entity-components
  "Returns an entity's components in update order."
  [entity-value]
  (entity/components entity-value))

(defn event
  "Creates a broadcast event, or a targeted event when entity-id is supplied."
  ([message] (event/event message))
  ([entity-id message] (event/event entity-id message)))

(defn send
  "Creates a handler output that sends a message to one entity."
  [entity-id message]
  (event/send entity-id message))

(defn broadcast
  "Creates a handler output that broadcasts a message in entity insertion order."
  [message]
  (event/broadcast message))

(defn effect
  "Creates a handler output containing an effect descriptor."
  [effect-value]
  (event/effect effect-value))

(defn spawn
  "Creates a handler output that adds an entity after the current event delivery."
  [entity-id entity-value]
  (runtime/spawn entity-id entity-value))

(defn despawn
  "Creates a handler output that removes an entity after the current event delivery."
  [entity-id]
  (runtime/despawn entity-id))

(defn replace-entity-command
  "Creates a handler output that replaces an entity after the current event delivery."
  [entity-id entity-value]
  (runtime/replace-entity-command entity-id entity-value))

(defn set-context
  "Creates a handler output that replaces context after the current event delivery."
  [context]
  (runtime/set-context context))

(defn assoc-context
  "Creates a handler output that associates one context value after event delivery."
  [key value]
  (runtime/assoc-context key value))

(defn dissoc-context
  "Creates a handler output that removes one context value after event delivery."
  [key]
  (runtime/dissoc-context key))

(defn switch-scene
  "Creates a handler output that replaces context and entities after the current event delivery."
  [context entity-entries]
  (runtime/switch-scene context entity-entries))

(defn step
  "Processes external events, then source events, in FIFO order.
  Returns {:game next-game :effects effect-values}."
  ([game sources dt external-events]
   (runtime/step game sources dt external-events))
  ([game sources dt external-events options]
   (runtime/step game sources dt external-events options)))

(defn render-data
  "Returns an eager vector of render data in entity insertion order."
  ([game] (runtime/render-data game))
  ([game render-context] (runtime/render-data game render-context)))

(defn snapshot
  "Returns context and typed entity state as plain snapshot data."
  [game]
  (runtime/snapshot game))

(defn restore
  "Restores snapshot data through a map of entity types to state factory functions."
  [snapshot factories]
  (runtime/restore snapshot factories))

(defn dispatch-effects!
  "Executes effect descriptors through handlers keyed by effect type."
  [handlers effects]
  (runtime/dispatch-effects! handlers effects))
