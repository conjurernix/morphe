(ns morphe.test-runner
  (:require [clojure.test :as test]
            [morphe.data-oriented-test]
            [morphe.property-test]
            [morphe.runtime-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'morphe.runtime-test
                                             'morphe.property-test
                                             'morphe.data-oriented-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
