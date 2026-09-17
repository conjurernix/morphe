(ns morphe.property-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [morphe.components.2d :as c2d]
            [morphe.core :as ecs]
            [morphe.query :as q]))

(def values (gen/vector (gen/choose 0 20) 1 10))

(defn build-world [numbers]
  (reduce
    (fn [current-world value]
      (first (ecs/spawn current-world {c2d/position-key (c2d/position value value)})))
    (ecs/world)
    numbers))

(deftest world-snapshots-remain-unchanged
  (let [result (tc/quick-check
                 100
                 (prop/for-all [numbers values]
                   (let [snapshot (build-world numbers)
                         derived (reduce
                                   (fn [current-world entity-id]
                                     (ecs/set-component current-world entity-id c2d/position-key
                                                        (c2d/position 1.0 1.0)))
                                   snapshot
                                   (q/query snapshot (q/all c2d/position-key)))]
                     (and (= snapshot (build-world numbers))
                          (not= snapshot derived)))))]
    (is (:pass? result) result)))

(deftest query-results-match-membership
  (let [result (tc/quick-check
                 100
                 (prop/for-all [numbers values]
                   (let [world (reduce
                                 (fn [current-world value]
                                   (first (ecs/spawn current-world
                                                     (if (even? value)
                                                       {c2d/position-key (c2d/position 0.0 0.0)}
                                                       {}))))
                                 (ecs/world)
                                 numbers)
                         matching (set (q/query world (q/all c2d/position-key)))]
                     (= matching
                        (set (for [entity-id (:entities world)
                                   :when (ecs/has-component? world entity-id c2d/position-key)]
                               entity-id))))))]
    (is (:pass? result) result)))
