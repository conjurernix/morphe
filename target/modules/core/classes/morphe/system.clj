(ns morphe.system
  (:require [morphe.event :as event]
            [morphe.query :as query]))

(defprotocol ExecutableSystem
  (execute-system [system world dt]))

(defprotocol SystemMetadata
  (system-metadata [system]))

(defprotocol CommandProcessor
  (process-commands [system world dt commands]))

(defn normalize-result
  [result]
  (if (and (map? result) (contains? result :world))
    {:world (:world result) :events (event/normalize (:events result))}
    {:world result :events []}))

(defn- metadata-from
  [{:keys [name reads writes resource-reads resource-writes before after]
    :or {reads #{} writes #{} resource-reads #{} resource-writes #{}}}]
  {:name name
   :reads (set reads)
   :writes (set writes)
   :resource-reads (set resource-reads)
   :resource-writes (set resource-writes)
   :before (set before)
   :after (set after)})

(defrecord WorldSystem [metadata operation]
  ExecutableSystem
  (execute-system [_ world dt]
    (normalize-result (operation world dt)))
  SystemMetadata
  (system-metadata [_]
    metadata))

(defrecord EntitySystem [metadata query operation]
  ExecutableSystem
  (execute-system [_ world dt]
    (reduce
      (fn [{:keys [world events]} entity-id]
        (if-not (contains? (:entities world) entity-id)
          {:world world :events events}
          (let [{next-world :world new-events :events}
                (normalize-result (operation world entity-id dt))]
            {:world next-world :events (into events new-events)})))
      {:world world :events []}
      (query/query world query)))
  SystemMetadata
  (system-metadata [_]
    metadata))

(defn system
  [{:keys [operation] :as options}]
  (when-not (fn? operation)
    (throw (ex-info "A system requires an :operation function"
                    {:name (:name options)})))
  (->WorldSystem (metadata-from options) operation))

(defn entity-system
  [{:keys [name query reads writes resource-reads resource-writes before after]
    :or {reads #{} writes #{} resource-reads #{} resource-writes #{}}}
   operation]
  (when-not query
    (throw (ex-info "An entity system requires a :query" {:name name})))
  (when-not (fn? operation)
    (throw (ex-info "An entity system requires an :operation function"
                    {:name name})))
  (->EntitySystem (metadata-from {:name name
                                  :reads reads
                                  :writes writes
                                  :resource-reads resource-reads
                                  :resource-writes resource-writes
                                  :before before
                                  :after after})
                  query
                  operation))

(defrecord CommandSystem [metadata command-types operation]
  CommandProcessor
  (process-commands [_ world dt commands]
    (reduce
      (fn [{:keys [world events]} command]
        (if-not (contains? command-types (:type command))
          {:world world :events events}
          (let [{next-world :world new-events :events}
                (normalize-result (operation world command dt))]
            {:world next-world :events (into events new-events)})))
      {:world world :events []}
      commands))
  SystemMetadata
  (system-metadata [_]
    metadata))

(defn command-system
  [{:keys [name command-types reads writes resource-reads resource-writes before after]
    :or {reads #{} writes #{} resource-reads #{} resource-writes #{}}}
   operation]
  (when-not (seq command-types)
    (throw (ex-info "A command system requires :command-types" {:name name})))
  (when-not (every? keyword? command-types)
    (throw (ex-info "Command types must be keywords" {:name name
                                                        :command-types command-types})))
  (when-not (fn? operation)
    (throw (ex-info "A command system requires an :operation function"
                    {:name name})))
  (->CommandSystem (metadata-from {:name name
                                   :reads reads
                                   :writes writes
                                   :resource-reads resource-reads
                                   :resource-writes resource-writes
                                   :before before
                                   :after after})
                   (set command-types)
                   operation))
