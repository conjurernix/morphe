(ns platformer.test-runner
  "Command-line entry point for the standalone platformer test suite.

  Run it with `clojure -M:test` from `examples/platformer`."
  (:require [clojure.test :as test]
            [platformer.game-test]))

(defn -main [& _]
  "Run the game tests and exit with status one when any test fails.

  The nonzero status lets shell scripts and continuous integration detect a
  failed example without parsing test output."
  (let [result (test/run-tests 'platformer.game-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
