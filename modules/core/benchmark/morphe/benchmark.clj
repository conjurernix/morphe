(ns morphe.benchmark
  (:require [morphe.component :as component]
            [morphe.components.2d :as c2d]
            [morphe.entity :as entity]
            [morphe.query :as query]
            [morphe.world :as world]))

(defn build-world [entity-count]
  (nth
    (iterate
      (fn [current-world]
        (first (entity/spawn current-world
                             {c2d/position-key (c2d/position 0.0 0.0)
                              c2d/velocity-key (c2d/velocity 1.0 0.0)})))
      (world/world))
    entity-count))

(defn -main [& _]
  (let [simulation-world (build-world 1000)]
    (time (query/query simulation-world (query/all c2d/position-key c2d/velocity-key)))
    (time (reduce
            (fn [current-world entity-id]
              (component/update-component current-world entity-id c2d/position-key update c2d/x + 1.0))
            simulation-world
            (query/query simulation-world (query/all c2d/position-key))))))
