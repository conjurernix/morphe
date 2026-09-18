(ns snake-event.game
  "Game construction and shared state helpers for event-driven Snake."
  (:require [morphe.core :as game]
            [snake-event.board :as board]
            [snake-event.food :as food]
            [snake-event.snake :as snake]))

(def grid board/grid)
(def cell-size board/cell-size)
(def snake-id snake/id)
(def food-id food/id)

(defn initial-game
  ([] (initial-game rand-nth))
  ([choose-cell]
   (let [food-position (board/choose-free-cell board/initial-snake-cells
                                               choose-cell)]
     (-> (game/game {::food/choose-cell choose-cell})
         (game/add-entity snake-id (snake/create food-id food-position))
         (game/add-entity food-id (food/create food-position))))))

(defn step
  ([game-state dt] (step game-state dt []))
  ([game-state dt events]
   (game/step game-state [] dt events)))

(defn snake-state [game-state]
  (snake/state game-state))

(defn food-state [game-state]
  (food/state game-state))

(defn score [game-state]
  (snake/score game-state))
