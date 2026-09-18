(ns morphe.quil-test-runner
  (:require [clojure.test :as test]
            [morphe.quil-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'morphe.quil-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
