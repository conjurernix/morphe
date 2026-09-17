(ns morphe.test-runner
  (:require [clojure.test :as test]
            [morphe.command-test]
            [morphe.core-test]
            [morphe.event-test]
            [morphe.game-e2e-test]
            [morphe.property-test]))

(defn -main [& _]
  (let [result (test/run-tests 'morphe.command-test
                               'morphe.core-test
                               'morphe.event-test
                               'morphe.game-e2e-test
                               'morphe.property-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
