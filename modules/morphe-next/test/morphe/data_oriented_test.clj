(ns morphe.data-oriented-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.benchmark.baseline :as baseline]
            [morphe.core :as data-oriented]))

(def baseline-api
  {:name :baseline
   :game baseline/game
   :entity baseline/entity
   :component baseline/component
   :add-entity baseline/add-entity
   :get-entity baseline/get-entity
   :entity-state baseline/entity-state
   :entity-components baseline/entity-components
   :event baseline/event
   :send baseline/send
   :effect baseline/effect
   :step baseline/step
   :render-data baseline/render-data})

(def data-oriented-api
  {:name :data-oriented
   :game data-oriented/game
   :entity data-oriented/entity
   :component data-oriented/component
   :add-entity data-oriented/add-entity
   :get-entity data-oriented/get-entity
   :entity-state data-oriented/entity-state
   :entity-components data-oriented/entity-components
   :event data-oriented/event
   :send data-oriented/send
   :effect data-oriented/effect
   :step data-oriented/step
   :render-data data-oriented/render-data})

(def implementations [baseline-api data-oriented-api])

(defn- run-scenario
  [{create-game :game
    create-entity :entity
    create-component :component
    add-entity :add-entity
    get-entity :get-entity
    entity-state :entity-state
    create-event :event
    send :send
    effect :effect
    step :step
    render-data :render-data}]
  (let [score-key ::score
        score (create-component
                score-key
                (fn [state _context [_ amount]]
                  [(update state score-key + amount) []]))
        player (create-entity
                 {:messages [] score-key 0}
                 [score]
                 (fn [state _context [message-type value]]
                   [(update state :messages conj [message-type value])
                    (cond-> []
                      (= :forward message-type)
                      (conj (send :player [:generated value]))
                      (= :effect message-type)
                      (conj (effect [:trace value])))]))
        observer (create-entity
                   {:messages []}
                   []
                   (fn [state _context message]
                     [(update state :messages conj message) []])
                   (fn [{:keys [messages]} _context _render-context]
                     [{:message-count (count messages)}]))
        initial (-> (create-game {:mode :test})
                    (add-entity :player player)
                    (add-entity :observer observer))
        source (fn [_context _dt] [(create-event :player [:source 3])])
        {:keys [game effects]}
        (step initial [source] 0.25
              [(create-event :player [:forward 1])
               (create-event [:broadcast 2])
               (create-event :player [:effect 4])])]
    {:context (:context game)
     :entity-order (:entity-order game)
     :player-state (entity-state (get-entity game :player))
     :observer-state (entity-state (get-entity game :observer))
     :effects effects
     :render-data (render-data game)}))

(defn- exception-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest data-oriented-runtime-matches-baseline-behavior
  (is (= (run-scenario baseline-api)
         (run-scenario data-oriented-api))))

(deftest data-oriented-public-functions-keep-contract-metadata
  (doseq [public-var [#'data-oriented/game #'data-oriented/entity
                      #'data-oriented/component #'data-oriented/add-entity
                      #'data-oriented/get-entity #'data-oriented/entity-state
                      #'data-oriented/entity-components #'data-oriented/event
                      #'data-oriented/send #'data-oriented/broadcast
                      #'data-oriented/effect #'data-oriented/step
                      #'data-oriented/render-data
                      #'data-oriented/dispatch-effects!]]
    (let [{:keys [doc arglists]} (meta public-var)]
      (is (seq doc))
      (is (seq arglists)))))

(deftest data-oriented-runtime-matches-error-contracts
  (doseq [{create-game :game
           create-entity :entity
           create-component :component
           add-entity :add-entity
           create-event :event
           send :send
           step :step}
          implementations]
    (testing "component ownership"
      (let [owned (create-component
                    ::owned
                    (fn [state _context _message]
                      [(assoc state :other 1) []]))
            game (add-entity
                   (create-game)
                   :player
                   (create-entity {::owned 0 :other 0} [owned]
                                  (fn [state _context _message] [state []])))
            data (exception-data
                   #(step game [] 0.0 [(create-event :player [:update])]))]
        (is (= :validation (:phase data)))
        (is (= :player (:entity-id data)))
        (is (= ::owned (:component-key data)))
        (is (= #{:other} (:changed-keys data)))))
    (testing "missing targets"
      (let [runtime-event (create-event :missing [:update])
            data (exception-data #(step (create-game) [] 0.0 [runtime-event]))]
        (is (= {:phase :delivery
                :entity-id :missing
                :event runtime-event}
               data))))
    (testing "event limits"
      (let [looping (create-entity
                      {}
                      []
                      (fn [state _context message]
                        [state [(send :loop message)]]))
            game (add-entity (create-game) :loop looping)
            data (exception-data
                   #(step game [] 0.0 [(create-event :loop [:again])]
                          {:max-events 3}))]
        (is (= :event-drain (:phase data)))
        (is (= 3 (:processed data)))))))
