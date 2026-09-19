(ns morphe.internal.runtime
  (:require [morphe.internal.entity :as entity]
            [morphe.internal.event :as event]))

(def default-max-events 10000)
(def snapshot-version 1)

(def spawn-tag ::spawn)
(def despawn-tag ::despawn)
(def replace-entity-tag ::replace-entity)
(def set-context-tag ::set-context)
(def assoc-context-tag ::assoc-context)
(def dissoc-context-tag ::dissoc-context)
(def switch-scene-tag ::switch-scene)

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :validation))))

(defn game
  ([]
   (game {}))
  ([context]
   (when-not (map? context)
     (fail! "Game context must be a map" {:value context}))
   {:context context :entities {} :entity-order []}))

(defn- valid-game?
  [{:keys [context entities entity-order] :as game}]
  (and (map? game)
       (map? context)
       (map? entities)
       (vector? entity-order)
       (every? some? entity-order)
       (every? entity/entity? (vals entities))
       (= (set entity-order) (set (keys entities)))
       (= (count entity-order) (count entities))))

(defn- validate-game!
  [game]
  (when-not (valid-game? game)
    (fail! "Game state is malformed" {:value game}))
  game)

(defn add-entity
  [{:keys [entities] :as game} entity-id entity-value]
  (validate-game! game)
  (when (nil? entity-id)
    (fail! "Entity IDs must be non-nil" {:entity-id entity-id}))
  (when-not (entity/entity? entity-value)
    (fail! "Entities must be created with entity" {:entity-id entity-id
                                                    :value entity-value}))
  (when (contains? entities entity-id)
    (fail! "Entity ID is already registered" {:entity-id entity-id}))
  (-> game
      (assoc-in [:entities entity-id] entity-value)
      (update :entity-order conj entity-id)))

(defn remove-entity
  [{:keys [entities] :as game} entity-id]
  (validate-game! game)
  (when-not (contains? entities entity-id)
    (fail! "Entity ID is not registered" {:entity-id entity-id}))
  (-> game
      (update :entities dissoc entity-id)
      (update :entity-order (fn [order]
                              (into [] (remove #(= entity-id %)) order)))))

(defn replace-entity
  [{:keys [entities] :as game} entity-id entity-value]
  (validate-game! game)
  (when-not (contains? entities entity-id)
    (fail! "Entity ID is not registered" {:entity-id entity-id}))
  (when-not (entity/entity? entity-value)
    (fail! "Entities must be created with entity" {:entity-id entity-id
                                                    :value entity-value}))
  (assoc-in game [:entities entity-id] entity-value))

(defn update-context
  [{:keys [context] :as game} f & args]
  (validate-game! game)
  (when-not (fn? f)
    (fail! "Context updater must be a function" {:value f}))
  (let [next-context (apply f context args)]
    (when-not (map? next-context)
      (fail! "Context updater must return a map" {:value next-context}))
    (assoc game :context next-context)))

(defn- validate-entity-entries!
  [entity-entries]
  (when-not (sequential? entity-entries)
    (fail! "Scene entities must be sequential ID and entity pairs"
           {:value entity-entries}))
  (doseq [entry entity-entries]
    (when-not (and (vector? entry) (= 2 (count entry)))
      (fail! "Scene entities must be ID and entity pairs" {:value entry})))
  entity-entries)

(defn replace-scene
  ([context entity-entries]
   (validate-entity-entries! entity-entries)
   (reduce (fn [next-game [entity-id entity-value]]
             (add-entity next-game entity-id entity-value))
           (game context)
           entity-entries))
  ([current-game context entity-entries]
   (validate-game! current-game)
   (replace-scene context entity-entries)))

(defn get-entity
  [{:keys [entities] :as game} entity-id]
  (validate-game! game)
  (get entities entity-id))

(defn spawn
  [entity-id entity-value]
  (when (nil? entity-id)
    (fail! "Entity IDs must be non-nil" {:entity-id entity-id}))
  (when-not (entity/entity? entity-value)
    (fail! "Entities must be created with entity" {:entity-id entity-id
                                                    :value entity-value}))
  [spawn-tag entity-id entity-value])

(defn despawn
  [entity-id]
  (when (nil? entity-id)
    (fail! "Entity IDs must be non-nil" {:entity-id entity-id}))
  [despawn-tag entity-id])

(defn replace-entity-command
  [entity-id entity-value]
  (when (nil? entity-id)
    (fail! "Entity IDs must be non-nil" {:entity-id entity-id}))
  (when-not (entity/entity? entity-value)
    (fail! "Entities must be created with entity" {:entity-id entity-id
                                                    :value entity-value}))
  [replace-entity-tag entity-id entity-value])

(defn set-context
  [context]
  (when-not (map? context)
    (fail! "Game context must be a map" {:value context}))
  [set-context-tag context])

(defn assoc-context
  [key value]
  [assoc-context-tag key value])

(defn dissoc-context
  [key]
  [dissoc-context-tag key])

(defn switch-scene
  [context entity-entries]
  (when-not (map? context)
    (fail! "Game context must be a map" {:value context}))
  (validate-entity-entries! entity-entries)
  [switch-scene-tag context (vec entity-entries)])

(defn- render-entity-data
  [{:keys [context entities]} entity-id render-context]
  (try
    (entity/render-entity
      (get entities entity-id)
      context
      (assoc render-context :morphe.render/entity-id entity-id))
    (catch #?(:clj clojure.lang.ExceptionInfo
   :cljs cljs.core/ExceptionInfo) cause
      (throw (ex-info (.getMessage cause)
                      (merge (ex-data cause)
                             {:phase :render :entity-id entity-id})
                      cause)))
    (catch #?(:clj Throwable :cljs :default) cause
      (throw (ex-info "Entity render handler failed"
                      {:phase :render :entity-id entity-id}
                      cause)))))

(defn render-data
  ([game]
   (render-data game {}))
  ([{:keys [entity-order] :as game} render-context]
   (validate-game! game)
   (when-not (map? render-context)
     (fail! "Render context must be a map" {:value render-context}))
   (into []
         (mapcat #(render-entity-data game % render-context))
         entity-order)))

(defn- valid-output?
  [output]
  (and (vector? output)
       (let [[output-type value message] output]
         (case output-type
           ::event/send (and (= 3 (count output))
                             (some? value)
                             (event/message? message))
           ::event/broadcast (and (= 2 (count output))
                                  (event/message? value))
           ::event/effect (and (= 2 (count output))
                               (event/effect-value? value))
           ::spawn (and (= 3 (count output))
                        (some? value)
                        (entity/entity? message))
           ::despawn (and (= 2 (count output))
                          (some? value))
           ::replace-entity (and (= 3 (count output))
                                 (some? value)
                                 (entity/entity? message))
           ::set-context (and (= 2 (count output))
                              (map? value))
           ::assoc-context (= 3 (count output))
           ::dissoc-context (= 2 (count output))
           ::switch-scene (and (= 3 (count output))
                               (map? value)
                               (sequential? message))
           false))))

(defn- route-output
  [result output]
  (when-not (valid-output? output)
    (fail! "Handlers returned an invalid output" {:value output}))
  (let [[output-type value message] output]
    (case output-type
      ::event/send (update result :queue conj
                           (event/event value message))
      ::event/broadcast (update result :queue conj
                                (event/event value))
      ::event/effect (update result :effects conj value)
      (update result :commands (fnil conj []) output))))

(defn- apply-command
  [result [command-type value argument :as command]]
  (try
    (case command-type
      ::spawn (update result :game add-entity value argument)
      ::despawn (update result :game remove-entity value)
      ::replace-entity (update result :game replace-entity value argument)
      ::set-context (assoc-in result [:game :context] value)
      ::assoc-context (assoc-in result [:game :context value] argument)
      ::dissoc-context (update-in result [:game :context] dissoc value)
      ::switch-scene (assoc result :game (replace-scene value argument)))
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core/ExceptionInfo) cause
      (throw (ex-info (.getMessage cause)
                      (assoc (ex-data cause) :phase :command :command command)
                      cause)))
    (catch #?(:clj Throwable :cljs :default) cause
      (throw (ex-info "Game command failed"
                      {:phase :command :command command}
                      cause)))))

(defn- apply-commands
  [result]
  (if (seq (:commands result))
    (-> (reduce apply-command result (:commands result))
        (dissoc :commands))
    result))

(defn- update-target
  [{{:keys [context entities]} :game :as result} entity-id message runtime-event]
  (let [current (get entities entity-id)]
    (when-not current
      (throw (ex-info "Event target does not exist"
                      {:phase :delivery
                       :entity-id entity-id
                       :event runtime-event})))
    (try
      (let [[updated outputs]
            (entity/update-entity current context message)
            next-result (assoc-in result [:game :entities entity-id] updated)]
        (reduce route-output next-result outputs))
      (catch #?(:clj clojure.lang.ExceptionInfo
   :cljs cljs.core/ExceptionInfo) cause
        (throw (ex-info (.getMessage cause)
                        (merge {:phase :delivery}
                               (ex-data cause)
                               {:entity-id entity-id
                                :event runtime-event})
                        cause)))
      (catch #?(:clj Throwable :cljs :default) cause
        (throw (ex-info "Entity handler failed"
                        {:phase :delivery
                         :entity-id entity-id
                         :event runtime-event}
                        cause))))))

(defn- deliver-event
  [{{:keys [entity-order]} :game :as result}
   {:keys [target message] :as runtime-event}]
  (let [targets (if (some? target) [target] entity-order)]
    (reduce #(update-target %1 %2 message runtime-event) result targets)))

(defn- drain
  [result max-events]
  (loop [current result
         processed 0]
    (let [{:keys [queue]} current]
      (if (empty? queue)
        (dissoc current :queue)
        (do
          (when (>= processed max-events)
            (throw (ex-info "Event limit exceeded while draining the queue"
                            {:phase :event-drain
                             :max-events max-events
                             :processed processed
                             :event (peek queue)})))
          (let [next-event (peek queue)
                without-event (update current :queue pop)]
            (recur (-> (deliver-event without-event next-event)
                       apply-commands)
                   (inc processed))))))))

(defn- finite-nonnegative-number?
  [value]
  (and (number? value)
       (not (neg? value))
       #?(:clj (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(defn- validate-event!
  [runtime-event data]
  (when-not (event/event? runtime-event)
    (fail! "Runtime events must be created with event" (assoc data :value runtime-event)))
  runtime-event)

(defn- source-events
  [{:keys [context]} sources dt]
  (into []
        (mapcat
          (fn [[index source]]
            (when-not (fn? source)
              (fail! "Event sources must be functions" {:source-index index
                                                         :value source}))
            (let [events (source context dt)]
              (when-not (sequential? events)
                (fail! "Event sources must return sequential events"
                       {:source-index index :value events}))
              (mapv #(validate-event! % {:source-index index}) events))))
        (map-indexed vector sources)))

(defn step
  ([game sources dt external-events]
   (step game sources dt external-events {}))
  ([game sources dt external-events {:keys [max-events]
                                     :or {max-events default-max-events}}]
   (validate-game! game)
   (when-not (sequential? sources)
     (fail! "Event sources must be sequential" {:value sources}))
   (when-not (finite-nonnegative-number? dt)
     (fail! "Step dt must be a finite, nonnegative number" {:value dt}))
   (when-not (sequential? external-events)
     (fail! "External events must be sequential" {:value external-events}))
   (when-not (pos-int? max-events)
     (fail! "Step :max-events must be a positive integer" {:value max-events}))
   (let [external-events (mapv #(validate-event! % {:event-index %2})
                               external-events
                               (range))
         source-events (source-events game sources dt)
         queue (into #?(:clj clojure.lang.PersistentQueue/EMPTY
                           :cljs cljs.core/PersistentQueue.EMPTY)
                     (concat external-events source-events))]
     (drain {:game game :queue queue :effects []} max-events))))

(defn snapshot
  [{:keys [context entity-order entities] :as game}]
  (validate-game! game)
  {:morphe.snapshot/version snapshot-version
   :context context
   :entities
   (mapv (fn [entity-id]
           (let [entity-value (get entities entity-id)
                 entity-type (entity/entity-type entity-value)]
             (when (nil? entity-type)
               (fail! "Snapshots require typed entities"
                      {:entity-id entity-id}))
             {:id entity-id
              :type entity-type
              :state (entity/state entity-value)}))
         entity-order)})

(defn restore
  [{version :morphe.snapshot/version
    :keys [context entities]
    :as snapshot-value}
   factories]
  (when-not (and (map? snapshot-value)
                 (= snapshot-version version)
                 (map? context)
                 (vector? entities))
    (fail! "Snapshot is malformed or has an unsupported version"
           {:value snapshot-value}))
  (when-not (map? factories)
    (fail! "Snapshot factories must be a map" {:value factories}))
  (reduce
    (fn [restored {:keys [id type state] :as saved-entity}]
      (when-not (and (map? saved-entity) (some? id) (some? type) (map? state))
        (fail! "Snapshot entity is malformed" {:value saved-entity}))
      (let [factory (get factories type)]
        (when-not (fn? factory)
          (fail! "No snapshot factory is registered for entity type"
                 {:entity-id id :entity-type type}))
        (try
          (let [entity-value (factory state)]
            (when-not (and (entity/entity? entity-value)
                           (= type (entity/entity-type entity-value)))
              (fail! "Snapshot factories must return a matching typed entity"
                     {:entity-id id :entity-type type :value entity-value}))
            (add-entity restored id entity-value))
          (catch #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core/ExceptionInfo) cause
            (throw (ex-info (.getMessage cause)
                            (assoc (ex-data cause)
                                   :phase :restore
                                   :entity-id id
                                   :entity-type type)
                            cause)))
          (catch #?(:clj Throwable :cljs :default) cause
            (throw (ex-info "Snapshot factory failed"
                            {:phase :restore
                             :entity-id id
                             :entity-type type}
                            cause))))))
    (game context)
    entities))

(defn dispatch-effects!
  [handlers effects]
  (when-not (map? handlers)
    (fail! "Effect handlers must be a map" {:value handlers}))
  (when-not (sequential? effects)
    (fail! "Effects must be sequential" {:value effects}))
  (let [dispatches
        (mapv
          (fn [effect-value]
            (when-not (event/effect-value? effect-value)
              (fail! "Effects must be vectors beginning with a keyword"
                     {:value effect-value}))
            (let [[effect-type & arguments] effect-value
                  handler (get handlers effect-type)]
              (when-not (fn? handler)
                (throw (ex-info "No effect handler registered"
                                {:phase :effect-dispatch :effect effect-value})))
              [handler arguments]))
          effects)]
    (doseq [[handler arguments] dispatches]
      (apply handler arguments))))
