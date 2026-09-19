(ns platformer.test-runner
  (:require [clojure.test :as test]
            [platformer.game-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'platformer.game-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
