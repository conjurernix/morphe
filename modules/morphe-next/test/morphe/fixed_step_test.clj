(ns morphe.fixed-step-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.core :as game]
            [morphe.fixed-step :as fixed-step]))

(defn- counter-game
  []
  (game/add-entity
    (game/game)
    :counter
    (game/entity
      {:ticks 0 :events []}
      []
      (fn [state _context [message-type & values]]
        (case message-type
          ::tick [(update state :ticks inc) []]
          ::record [(update state :events conj (first values)) []]
          [state []])))))

(def tick-source
  (fn [_context _dt]
    [(game/event :counter [::tick])]))

(defn- counter-state
  [loop-state]
  (-> loop-state :game (game/get-entity :counter) game/entity-state))

(deftest events-wait-for-the-next-fixed-update
  (let [initial (fixed-step/create (counter-game) {:update-hz 10})
        pending (fixed-step/advance
                  initial [] 0.04
                  [(game/event :counter [::record :input])])
        advanced (fixed-step/advance (:loop pending) [] 0.06 [])]
    (is (zero? (:updates pending)))
    (is (< (Math/abs (- 0.4 (:alpha pending))) 1.0e-9))
    (is (= [:input] (-> advanced :loop counter-state :events)))
    (is (= [{:tick 1
             :events [(game/event :counter [::record :input])]}]
           (:ticks advanced)))))

(deftest catch-up-is-bounded-and-reports-dropped-time
  (let [result (fixed-step/advance
                 (fixed-step/create
                   (counter-game)
                   {:update-hz 10
                    :max-catch-up-updates 2
                    :max-frame-seconds 0.5})
                 [tick-source]
                 0.8
                 [])]
    (is (= 2 (:updates result)))
    (is (= 2 (-> result :loop counter-state :ticks)))
    (is (< (Math/abs (- 0.6 (:dropped-seconds result))) 1.0e-9))
    (is (< (:alpha result) 1.0))))

(deftest catch-up-input-runs-once-and-effects-retain-order
  (let [value (game/entity
                {:seen []} []
                (fn [state _context [_ value]]
                  [(update state :seen conj value)
                   [(game/effect [:trace value])]]))
        initial-game (game/add-entity (game/game) :value value)
        source (fn [_context _dt] [(game/event :value [:record :source])])
        result (fixed-step/advance
                 (fixed-step/create initial-game {:update-hz 10})
                 [source]
                 0.2
                 [(game/event :value [:record :input])])]
    (is (= [:input :source :source]
           (-> result :loop :game (game/get-entity :value)
               game/entity-state :seen)))
    (is (= [[:trace :input] [:trace :source] [:trace :source]]
           (:effects result)))
    (is (= 2 (count (:ticks result))))
    (is (empty? (-> result :ticks second :events)))))

(deftest elapsed-partitions-produce-equal-state
  (let [initial (fixed-step/create (counter-game) {:update-hz 60})
        one-frame (:loop (fixed-step/advance initial [tick-source] 0.05 []))
        three-frames (reduce (fn [loop-state elapsed]
                               (:loop (fixed-step/advance loop-state
                                                          [tick-source]
                                                          elapsed
                                                          [])))
                             initial
                             [0.01 0.02 0.02])]
    (is (= 3 (:tick one-frame)))
    (is (= one-frame three-frames))))

(deftest reset-timing-drops-stale-time-and-input
  (let [initial (fixed-step/create (counter-game) {:update-hz 10})
        pending (:loop (fixed-step/advance
                         initial [] 0.05
                         [(game/event :counter [::record :stale])]))
        reset-state (fixed-step/reset-timing pending)
        result (fixed-step/advance reset-state [] 0.1 [])]
    (is (empty? (:pending-events reset-state)))
    (is (zero? (:accumulator-seconds reset-state)))
    (is (empty? (-> result :loop counter-state :events)))))

(deftest events-can-queue-while-the-clock-is-stopped
  (let [initial (fixed-step/create (counter-game) {:update-hz 10})
        queued (fixed-step/queue-events
                 initial [(game/event :counter [::record :application])])
        result (fixed-step/advance queued [] 0.1 [])]
    (is (= [:application] (-> result :loop counter-state :events)))))

(deftest scheduler-validates-public-input
  (let [initial (fixed-step/create (counter-game))]
    (testing "options"
      (is (thrown? clojure.lang.ExceptionInfo
                   (fixed-step/create (counter-game) {:update-hz 0}))))
    (testing "time, sources, and events"
      (is (thrown? clojure.lang.ExceptionInfo
                   (fixed-step/advance initial [] -1.0 [])))
      (is (thrown? clojure.lang.ExceptionInfo
                   (fixed-step/advance initial [:bad] 0.0 [])))
      (is (thrown? clojure.lang.ExceptionInfo
                   (fixed-step/advance initial [] 0.0 [:bad]))))))
