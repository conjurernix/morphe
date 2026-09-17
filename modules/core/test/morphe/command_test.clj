(ns morphe.command-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.command :as command]
            [morphe.core :as ecs]
            [morphe.runtime :as runtime]
            [morphe.system :as system]))

(deftest constructs-and-validates-commands
  (let [value (command/command :game/move-forward {:entity 7})]
    (is (= {:type :game/move-forward :entity 7} value))
    (is (command/command? value))
    (is (ecs/command? value))))

(deftest command-systems-process-only-declared-types
  (let [handled ::handled
        processor (system/command-system
                    {:name :test/processor :command-types #{:game/accepted}}
                    (fn [world current-command _dt]
                      (ecs/set-resource world handled (:value current-command))))
        current-runtime (runtime/runtime {:command-systems [processor]
                                          :systems []})
        result (ecs/step current-runtime (ecs/world) 0.016
                         [(command/command :game/ignored {:value 1})
                          (command/command :game/accepted {:value 2})])]
    (is (= 2 (ecs/get-resource (:world result) handled)))
    (is (= [] (:events result)))))

(deftest commands-are-transient
  (let [handled ::handled
        processor (system/command-system
                    {:name :test/processor :command-types #{:game/accepted}}
                    (fn [world _command _dt]
                      (ecs/update-resource world handled (fnil inc 0))))
        current-runtime (runtime/runtime {:command-systems [processor]
                                          :systems []})
        first-result (ecs/step current-runtime (ecs/world) 0.016
                                [(command/command :game/accepted)])
        second-result (ecs/step current-runtime (:world first-result) 0.016)]
    (is (= 1 (ecs/get-resource (:world first-result) handled)))
    (is (= 1 (ecs/get-resource (:world second-result) handled)))))

(deftest rejects-invalid-commands
  (testing "command types"
    (is (thrown? clojure.lang.ExceptionInfo (command/command "game/invalid"))))
  (testing "command payloads"
    (is (thrown? clojure.lang.ExceptionInfo (command/command :game/invalid []))))
  (testing "command collections"
    (is (thrown? clojure.lang.ExceptionInfo
                 (command/normalize [{:type :game/valid}
                                     {:type "game/invalid"}])))))
