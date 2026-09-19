(ns morphe.test-runner
  (:require [clojure.test :as test]
            [morphe.application-test]
            [morphe.collision-test]
            [morphe.data-oriented-test]
            [morphe.fixed-step-test]
            [morphe.property-test]
            [morphe.random-test]
            [morphe.replay-test]
            [morphe.runtime-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'morphe.runtime-test
                                             'morphe.property-test
                                             'morphe.data-oriented-test
                                             'morphe.fixed-step-test
                                             'morphe.application-test
                                             'morphe.collision-test
                                             'morphe.random-test
                                             'morphe.replay-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
