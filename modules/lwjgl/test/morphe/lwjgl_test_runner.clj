(ns morphe.lwjgl-test-runner
  (:require [clojure.test :as test]
            [morphe.lwjgl-test]))

(defn -main [& _]
  (let [result (test/run-tests 'morphe.lwjgl-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
