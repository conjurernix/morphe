(ns morphe.benchmark.baseline
  "Preserves the pre-refactor engine for benchmark comparisons."
  (:refer-clojure :exclude [send])
  (:require [morphe.benchmark.baseline.entity :as entity]
            [morphe.internal.event :as event]
            [morphe.benchmark.baseline.runtime :as runtime]))

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

(defn component
  "Creates a component that owns state under its namespace-qualified key."
  [key handler]
  (entity/component key handler))

(defn add-entity
  "Adds an entity under a unique, non-nil ID and preserves insertion order."
  [game entity-id entity-value]
  (runtime/add-entity game entity-id entity-value))

(defn get-entity
  "Returns the entity registered under entity-id, or nil when absent."
  [game entity-id]
  (runtime/get-entity game entity-id))

(defn entity-state
  "Returns an entity's local state map."
  [entity-value]
  (entity/state entity-value))

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

(defn dispatch-effects!
  "Executes effect descriptors through handlers keyed by effect type."
  [handlers effects]
  (runtime/dispatch-effects! handlers effects))
