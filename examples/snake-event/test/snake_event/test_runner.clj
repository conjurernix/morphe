(ns snake-event.test-runner
  (:require [clojure.test :as test]
            [snake-event.game-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'snake-event.game-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
