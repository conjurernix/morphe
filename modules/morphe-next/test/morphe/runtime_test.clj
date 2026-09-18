(ns morphe.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.core :as game]))

(defn- inert-entity
  ([]
   (inert-entity {}))
  ([state]
   (game/entity state [] (fn [current _context _message] [current []]))))

(defmulti multimethod-handler
  (fn [_state _context [message-type]] message-type))

(defmethod multimethod-handler :default
  [state _context _message]
  [state []])

(deftest public-api-keeps-documentation-and-argument-lists
  (doseq [public-var [#'game/game #'game/entity #'game/component #'game/add-entity
                      #'game/get-entity #'game/entity-state #'game/entity-components
                      #'game/event #'game/send #'game/broadcast #'game/effect
                      #'game/step #'game/render-data #'game/dispatch-effects!]]
    (let [{:keys [doc arglists]} (meta public-var)]
      (is (seq doc))
      (is (seq arglists)))))

(deftest entities-retain-insertion-order
  (let [trace-entity (fn [id]
                       (game/entity {} []
                                    (fn [state _context _message]
                                      [state [(game/effect [:trace/entity id])]])
                                    (fn [_state _context _render-context]
                                      [{:entity id}])))
        initial (-> (game/game)
                    (game/add-entity :second (trace-entity :second))
                    (game/add-entity :first (trace-entity :first)))
        {:keys [entity-order]} initial
        {:keys [effects]} (game/step initial [] 0.0 [(game/event [:trace])])]
    (is (= [:second :first] entity-order))
    (is (= [[:trace/entity :second] [:trace/entity :first]] effects))
    (is (= [{:entity :second} {:entity :first}] (game/render-data initial)))
    (is (vector? (game/render-data initial)))))

(deftest external-events-run-before-source-events
  (let [recorder (game/entity {:messages []} []
                              (fn [state _context [message-type]]
                                [(update state :messages conj message-type) []]))
        initial (game/add-entity (game/game) :recorder recorder)
        source (fn [_context _dt] [(game/event :recorder [:tick])])
        result (game/step initial [source] 0.1
                          [(game/event :recorder [:input])])]
    (is (= [:input :tick]
           (-> result :game (game/get-entity :recorder)
               game/entity-state :messages)))))

(deftest generated-events-are-fifo
  (let [forwarder (game/entity {:messages []} []
                               (fn [state _context [_ value]]
                                 [(update state :messages conj value)
                                  (cond-> []
                                    (= value :start)
                                    (conj (game/send :forwarder [:record :generated])))]))
        initial (game/add-entity (game/game) :forwarder forwarder)
        result (game/step initial [] 0.0
                          [(game/event :forwarder [:record :start])
                           (game/event :forwarder [:record :external])])]
    (is (= [:start :external :generated]
           (-> result :game (game/get-entity :forwarder)
               game/entity-state :messages)))))

(deftest components-own-one-namespaced-state-key
  (let [score-key ::score
        score (game/component score-key
                              (fn [state _context _message]
                                [(update state score-key inc) []]))
        value (game/entity {score-key 0 :name "Ada"} [score]
                           (fn [state _context _message] [state []]))
        initial (game/add-entity (game/game) :player value)
        result (game/step initial [] 0.0 [(game/event :player [:score])])]
    (is (= 1 (-> result :game (game/get-entity :player)
                 game/entity-state score-key)))
    (is (= [score] (game/entity-components value)))))

(deftest component-cannot-change-another-key
  (let [bad-component (game/component ::score
                                      (fn [state _context _message]
                                        [(assoc state :name "Grace") []]))
        initial (game/add-entity
                  (game/game)
                  :player
                  (game/entity {::score 0 :name "Ada"} [bad-component]
                               (fn [state _context _message] [state []])))]
    (try
      (game/step initial [] 0.0 [(game/event :player [:score])])
      (is false "Expected component ownership validation")
      (catch clojure.lang.ExceptionInfo error
        (let [{:keys [changed-keys component-key entity-id]} (ex-data error)]
          (is (= #{:name} changed-keys))
          (is (= ::score component-key))
          (is (= :player entity-id)))))))

(deftest entity-handler-cannot-change-component-state
  (let [owned-key ::owned
        owned (game/component owned-key (fn [state _context _message] [state []]))
        initial (game/add-entity
                  (game/game)
                  :player
                  (game/entity {owned-key 0} [owned]
                               (fn [state _context _message]
                                 [(update state owned-key inc) []])))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"may not change component-owned state"
                          (game/step initial [] 0.0
                                     [(game/event :player [:change])])))))

(deftest constructors-reject-invalid-contracts
  (testing "component keys are qualified and unique"
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/component :score (fn [state _ _] [state []]))))
    (let [component (game/component ::score (fn [state _ _] [state []]))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (game/entity {::score 0} [component component]
                                (fn [state _ _] [state []]))))))
  (testing "IDs and handlers are validated"
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/add-entity (game/game) nil (inert-entity))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/entity {} [] :not-a-function))))
  (testing "messages and effects are keyword-led vectors"
    (is (thrown? clojure.lang.ExceptionInfo (game/event :invalid)))
    (is (thrown? clojure.lang.ExceptionInfo (game/effect ["sound/play"]))))
  (testing "duplicate entity IDs are rejected"
    (let [initial (game/add-entity (game/game) :player (inert-entity))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (game/add-entity initial :player (inert-entity)))))))

(deftest entities-accept-multimethod-handlers
  (is (some? (game/entity {} [] multimethod-handler))))

(deftest step-validates-boundaries
  (let [initial (game/add-entity (game/game) :player (inert-entity))]
    (doseq [dt [-0.1 Double/POSITIVE_INFINITY Double/NaN]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (game/step initial [] dt []))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/step initial [] 0.0 [{:target :player :message :bad}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/step initial [(fn [_ _] :bad)] 0.0 [])))
    (let [bad-result (game/entity {} [] (fn [_ _ _] :bad))
          bad-game (game/add-entity (game/game) :bad bad-result)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (game/step bad-game [] 0.0 [(game/event :bad [:run])]))))
    (let [bad-output (game/entity {} [] (fn [state _ _] [state [:bad]]))
          bad-game (game/add-entity (game/game) :bad bad-output)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (game/step bad-game [] 0.0 [(game/event :bad [:run])]))))))

(deftest every-game-boundary-rejects-malformed-state
  (let [malformed {:context {} :entities {} :entity-order [:missing]}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/add-entity malformed :player (inert-entity))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/get-entity malformed :player)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/render-data malformed)))))

(deftest render-errors-identify-the-entity
  (let [value (game/entity {} []
                           (fn [state _context _message] [state []])
                           (fn [_state _context _render-context] :invalid))
        initial (game/add-entity (game/game) :player value)]
    (try
      (game/render-data initial)
      (is false "Expected render validation")
      (catch clojure.lang.ExceptionInfo error
        (let [{:keys [phase entity-id]} (ex-data error)]
          (is (= :render phase))
          (is (= :player entity-id)))))))

(deftest missing-targets-fail-with-event-context
  (let [runtime-event (game/event :missing [:run])]
    (try
      (game/step (game/game) [] 0.0 [runtime-event])
      (is false "Expected a missing-target failure")
      (catch clojure.lang.ExceptionInfo error
        (let [{:keys [phase entity-id event]} (ex-data error)]
          (is (= :delivery phase))
          (is (= :missing entity-id))
          (is (= runtime-event event)))))))

(deftest event-limit-stops-cycles
  (let [looping (game/entity {} []
                             (fn [state _context message]
                               [state [(game/send :loop message)]]))
        initial (game/add-entity (game/game) :loop looping)]
    (try
      (game/step initial [] 0.0 [(game/event :loop [:again])]
                 {:max-events 3})
      (is false "Expected an event-limit failure")
      (catch clojure.lang.ExceptionInfo error
        (let [{:keys [phase processed]} (ex-data error)]
          (is (= :event-drain phase))
          (is (= 3 processed)))))))

(deftest effects-remain-data-until-dispatched
  (let [calls (atom [])
        value (game/entity {} []
                           (fn [state _context _message]
                             [state [(game/effect [:sound/play :coin])]]))
        initial (game/add-entity (game/game) :player value)
        {:keys [effects]} (game/step initial [] 0.0
                                     [(game/event :player [:play])])]
    (is (empty? @calls))
    (game/dispatch-effects! {:sound/play #(swap! calls conj %)} effects)
    (is (= [:coin] @calls))
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/dispatch-effects! {} effects)))))

(deftest effect-handlers-are-validated-before-dispatch
  (let [calls (atom [])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/dispatch-effects!
                   {:sound/play #(swap! calls conj %)}
                   [[:sound/play :coin] [:screen/shake 2]])))
    (is (empty? @calls))
    (is (thrown? clojure.lang.ExceptionInfo
                 (game/dispatch-effects! {} [:bad])))))
