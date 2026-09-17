(ns morphe.quil-test-runner
  (:require [clojure.test :as test]
            [morphe.quil-test]))

(defn -main [& _]
  (let [result (test/run-tests 'morphe.quil-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
