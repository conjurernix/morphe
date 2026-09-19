(ns morphe.lwjgl-test-runner
  (:require [clojure.test :as test]
            [morphe.lwjgl-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'morphe.lwjgl-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
