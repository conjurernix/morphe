(ns platformer.game-test
  (:require [clojure.test :refer [deftest is]]
            [morphe.components.2d :as c2d]
            [morphe.core :as ecs]
            [morphe.query :as query]
            [platformer.game :as game]))

(defn step [world seconds]
  (:world (ecs/step game/runtime world seconds)))

(deftest initial-world-has-playable-state
  (let [world (game/initial-world)]
    (is (= :playing (:phase (ecs/get-resource world game/state-key))))
    (is (= 4 (:total-coins (ecs/get-resource world game/state-key))))
    (is (= 1 (count (filter #(ecs/has-component? world % game/player-key) (:entities world)))))))

(deftest player-falls-and-lands
  (let [world (game/initial-world)
        player (game/player-entity world)
        falling (ecs/set-component world player c2d/position-key (c2d/position 90.0 300.0))
        falling (ecs/set-component falling player game/grounded-key false)
        landed (step falling 0.5)
        position (ecs/component landed player c2d/position-key)]
    (is (= 457.0 (get position c2d/y)))
    (is (true? (ecs/component landed player game/grounded-key)))))

(deftest jump-changes-vertical-velocity
  (let [world (game/initial-world)
        player (game/player-entity world)
        world (ecs/set-resource world game/input-key #{:jump})
        world (ecs/set-component world player game/grounded-key true)
        jumped (step world 0.01)
        velocity (ecs/component jumped player c2d/velocity-key)]
    (is (neg? (get velocity c2d/y)))))

(deftest coin-collection-updates-score
  (let [world (game/initial-world)
        player (game/player-entity world)
        coin (first (query/query world (query/all game/coin-key game/bounds-key c2d/position-key)))
        coin-position (ecs/component world coin c2d/position-key)
        world (ecs/set-component world player c2d/position-key coin-position)
        collected (step world 0.0)]
    (is (= 1 (:score (ecs/get-resource collected game/state-key))))
    (is (not (ecs/alive? collected coin)))))

(deftest enemy-contact-loses
  (let [world (game/initial-world)
        player (game/player-entity world)
        enemy (first (query/query world (query/all game/enemy-key game/bounds-key c2d/position-key)))
        enemy-position (ecs/component world enemy c2d/position-key)
        world (ecs/set-component world player c2d/position-key enemy-position)
        lost (step world 0.0)]
    (is (= :lost (:phase (ecs/get-resource lost game/state-key))))))

(deftest restart-returns-playing-state
  (let [world (ecs/set-resource (game/initial-world) game/state-key {:phase :won :score 4 :total-coins 4})
        world (ecs/set-resource world game/input-key #{:restart})
        restarted (step world 0.0)]
    (is (= :playing (:phase (ecs/get-resource restarted game/state-key))))
    (is (= 0 (:score (ecs/get-resource restarted game/state-key))))))
