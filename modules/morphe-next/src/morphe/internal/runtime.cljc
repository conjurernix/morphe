(ns morphe.internal.runtime
  (:require [morphe.internal.entity :as entity]
            [morphe.internal.event :as event]))

(def default-max-events 10000)

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

(defn get-entity
  [{:keys [entities] :as game} entity-id]
  (validate-game! game)
  (get entities entity-id))

(defn- render-entity-data
  [{:keys [context entities]} entity-id render-context]
  (try
    (entity/render-entity (get entities entity-id) context render-context)
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
      ::event/effect (update result :effects conj value))))

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
            (recur (deliver-event without-event next-event) (inc processed))))))))

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
