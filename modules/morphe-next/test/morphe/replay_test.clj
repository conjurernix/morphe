(ns morphe.replay-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [morphe.core :as game]
            [morphe.fixed-step :as fixed-step]
            [morphe.random :as random]
            [morphe.replay :as replay]))

(defn- create-counter
  [state]
  (game/typed-entity
    :counter state []
    (fn [{:keys [random] :as current} _context [message-type amount]]
      (case message-type
        :counter/add [(update current :total + amount) []]
        :counter/random
        (let [[next-random value] (random/next-int random amount)]
          [(-> current
               (assoc :random next-random)
               (update :draws conj value))
           []])
        [current []]))))

(def factories {:counter create-counter})

(defn- initial-game
  []
  (game/add-entity
    (game/game {:mode :test})
    :counter
    (create-counter {:total 0 :draws [] :random (random/create 42)})))

(deftest recordings-round-trip-and-replay-to-the-same-snapshot
  (let [initial (initial-game)
        loop-state (fixed-step/create initial {:update-hz 10})
        first-frame (fixed-step/advance
                      loop-state [] 0.1
                      [(game/event :counter [:counter/add 3])])
        second-frame (fixed-step/advance
                       (:loop first-frame) [] 0.2
                       [(game/event :counter [:counter/random 10])])
        recording (-> (replay/start-recording (game/snapshot initial) 10)
                      (replay/record-ticks (:ticks first-frame))
                      (replay/record-ticks (:ticks second-frame))
                      (replay/finish-recording
                        (game/snapshot (-> second-frame :loop :game))))
        serialized (pr-str recording)
        result (replay/play (edn/read-string serialized) factories [])]
    (is (= recording (edn/read-string serialized)))
    (is (= (:final-snapshot recording) (:snapshot result)))
    (is (= 3 (-> result :game (game/get-entity :counter)
                 game/entity-state :total)))
    (is (= 1 (count (-> result :game (game/get-entity :counter)
                        game/entity-state :draws))))))

(deftest replay-rejects-divergence-and-invalid-ticks
  (let [initial-snapshot (game/snapshot (initial-game))
        recording (replay/start-recording initial-snapshot 60)]
    (testing "tick sequence"
      (is (thrown? clojure.lang.ExceptionInfo
                   (replay/record-ticks
                     recording
                     [{:tick 2 :events []}]))))
    (testing "final state"
      (let [finished (replay/finish-recording recording initial-snapshot)
            changed (assoc-in finished
                              [:final-snapshot :context :mode]
                              :different)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (replay/play changed factories [])))))))

(deftest replay-rejects-non-edn-state
  (let [bad-snapshot {:morphe.snapshot/version 1
                      :context {:value (Object.)}
                      :entities []}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (replay/start-recording bad-snapshot 60)))))

