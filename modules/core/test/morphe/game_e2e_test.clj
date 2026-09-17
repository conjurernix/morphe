(ns morphe.game-e2e-test
  (:require [clojure.test :refer [deftest is]]
            [morphe.components.2d :as c2d]
            [morphe.command :as command]
            [morphe.core :as ecs]
            [morphe.event :as event]
            [morphe.query :as q]
            [morphe.runtime :as runtime]
            [morphe.system :as system]))

(def score ::score)
(def player ::player)
(def enemy ::enemy)
(def moved ::moved)
(def enemy-defeated ::enemy-defeated)
(def score-changed ::score-changed)
(def move-forward ::move-forward)

(def apply-input
  (system/command-system
    {:name :game/apply-input
     :command-types #{move-forward}
     :writes #{c2d/velocity-key}}
    (fn [world current-command _dt]
      (ecs/set-component
        world
        (:entity current-command)
        c2d/velocity-key
        (c2d/velocity 10.0 0.0)))))

(def movement
  (system/entity-system
    {:name :game/movement
     :query (q/all c2d/position-key c2d/velocity-key)}
    (fn [world entity dt]
      (let [position (ecs/component world entity c2d/position-key)
            velocity (ecs/component world entity c2d/velocity-key)
            next-position
            {c2d/x (+ (get position c2d/x) (* (get velocity c2d/x) dt))
             c2d/y (+ (get position c2d/y) (* (get velocity c2d/y) dt))}
            next-world (ecs/set-component world entity c2d/position-key next-position)]
        {:world next-world
         :events [(event/event moved {:entity entity :position next-position})]}))))

(def combat
  (system/system
    {:name :game/combat
     :reads #{c2d/position-key player enemy}
     :writes #{score}
     :operation (fn [world _dt]
                  (let [player-entity (first (q/query world (q/all player c2d/position-key)))
                        enemy-entity (first (q/query world (q/all enemy c2d/position-key)))
                        player-position (ecs/component world player-entity c2d/position-key)
                        enemy-position (ecs/component world enemy-entity c2d/position-key)
                        next-score (inc (ecs/get-resource world score 0))]
                    (if (and player-entity enemy-entity (= player-position enemy-position))
                      {:world (-> world
                                  (ecs/destroy enemy-entity)
                                  (ecs/update-resource score inc))
                       :events [(event/event enemy-defeated {:entity enemy-entity})
                                (event/event score-changed {:score next-score})]}
                      {:world world :events []})))}))

(deftest game-frame-moves-player-and-defeats-enemy
  (let [initial-world (ecs/set-resource (ecs/world) score 0)
        [world player-entity] (ecs/spawn
                                initial-world
                                {player true
                                 c2d/position-key (c2d/position 0.0 0.0)
                                 c2d/velocity-key (c2d/velocity 0.0 0.0)})
        [world enemy-entity] (ecs/spawn
                               world
                               {enemy true
                                c2d/position-key (c2d/position 10.0 0.0)
                                c2d/velocity-key (c2d/velocity 0.0 0.0)})
        game-runtime (runtime/runtime {:command-systems [apply-input]
                                       :systems [movement combat]})
        first-result (ecs/step game-runtime world 1.0
                               [(command/command move-forward {:entity player-entity})])
        first-world (:world first-result)
        second-result (ecs/step game-runtime first-world 0.5)
        second-world (:world second-result)]
    (is (satisfies? system/ExecutableSystem movement))
    (is (satisfies? system/ExecutableSystem combat))
    (is (= (c2d/position 10.0 0.0)
           (ecs/component first-world player-entity c2d/position-key)))
    (is (ecs/alive? first-world player-entity))
    (is (not (ecs/alive? first-world enemy-entity)))
    (is (= 1 (ecs/get-resource first-world score)))
    (is (= [] (q/query first-world (q/all enemy c2d/position-key))))
    (is (= [{:type moved
             :entity player-entity
             :position (c2d/position 10.0 0.0)}
            {:type moved
             :entity enemy-entity
             :position (c2d/position 10.0 0.0)}
            {:type enemy-defeated :entity enemy-entity}
            {:type score-changed :score 1}]
           (:events first-result)))
    (is (= (c2d/position 15.0 0.0)
           (ecs/component second-world player-entity c2d/position-key)))
    (is (ecs/alive? second-world player-entity))
    (is (not (ecs/alive? second-world enemy-entity)))
    (is (= 1 (ecs/get-resource second-world score)))
    (is (= [{:type moved
             :entity player-entity
             :position (c2d/position 15.0 0.0)}]
           (:events second-result)))
    (is (= (c2d/position 0.0 0.0)
           (ecs/component world player-entity c2d/position-key)))
    (is (ecs/alive? world enemy-entity))
    (is (= 0 (ecs/get-resource world score)))
    (is (= (c2d/position 10.0 0.0)
           (ecs/component first-world player-entity c2d/position-key)))
    (is (= 4 (count (:events first-result))))))
