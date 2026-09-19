(ns platformer.entities.coin
  (:require [morphe.core :as game]
            [platformer.world :as world]))

(def id :coin)
(def entity-type ::coin)

(defn update-state
  [state _context [message-type]]
  (if (= message-type ::collect)
    [(assoc state :collected? true) []]
    [state []]))

(defn render
  [{:keys [collected?]} _context _render-context]
  (if collected?
    []
    [{:kind :sprite
      :asset :coin
      :x (first world/coin-position)
      :y (second world/coin-position)
      :width 24
      :height 24
      :layer 2}]))

(defn create-entity
  [state]
  (game/typed-entity entity-type state [] update-state render))

(defn create
  []
  [id (create-entity {:collected? false})])
