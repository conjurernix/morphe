(ns morphe.event-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.core :as ecs]
            [morphe.event :as event]))

(deftest constructs-events-with-namespaced-types
  (let [value (event/event :game/damaged {:entity 7 :amount 2})]
    (is (= {:type :game/damaged :entity 7 :amount 2} value))
    (is (event/event? value))
    (is (ecs/event? value))))

(deftest normalizes-single-and-multiple-events
  (let [first-event (event/event :game/started)
        second-event (event/event :game/finished)]
    (is (= [first-event] (event/normalize first-event)))
    (is (= [first-event second-event]
           (event/normalize [first-event second-event])))
    (is (= [] (event/normalize nil)))))

(deftest rejects-invalid-events
  (testing "event types"
    (is (thrown? clojure.lang.ExceptionInfo (event/event "game/damaged"))))
  (testing "event payloads"
    (is (thrown? clojure.lang.ExceptionInfo (event/event :game/damaged []))))
  (testing "event collections"
    (is (thrown? clojure.lang.ExceptionInfo
                 (event/normalize [{:type :game/valid} {:type "game/invalid"}])))))
