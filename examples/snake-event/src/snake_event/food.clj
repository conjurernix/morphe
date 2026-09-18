(ns snake-event.food
  (:require [morphe.core :as game]
            [snake-event.board :as board]
            [snake-event.components.position :as position]
            [snake-event.snake :as-alias snake]))

(def id :food)

(defn initial-state
  [initial-position]
  {position/position-key initial-position})

(defmulti update-food
  (fn [_state _context [message-type]] message-type))

(defmethod update-food ::eaten
  [state {::keys [choose-cell]} [_ snake-id occupied-cells]]
  (if-let [position (board/choose-free-cell occupied-cells choose-cell)]
    [state [(game/send id [::position/set position snake-id
                           [::snake/food-moved position]])
            (game/send snake-id [::snake/food-eaten])]]
    [state [(game/send snake-id [::snake/won])]]))

(defmethod update-food ::restart
  [state {::keys [choose-cell]} [_ snake-id occupied-cells]]
  (if-let [position (board/choose-free-cell occupied-cells choose-cell)]
    [state [(game/send id [::position/restart position snake-id
                           [::snake/food-moved position]])]]
    [state [(game/send snake-id [::snake/won])]]))

(defmethod update-food :default
  [state _context _message]
  [state []])

(defn- render-food
  [state _context {render-cell-size :cell-size}]
  (let [[x y] (get state position/position-key)
        size (or render-cell-size board/cell-size)]
    [{:kind :rectangle :layer 0
      :x (* (+ x 0.5) size) :y (* (+ y 0.5) size)
      :width size :height size :fill [255 211 64]}]))

(defn create
  [initial-position]
  (game/entity (initial-state initial-position)
               [(position/component initial-position)]
               update-food
               render-food))

(defn state [game-state]
  (game/entity-state (game/get-entity game-state id)))

(defn position [game-state]
  (get (state game-state) position/position-key))
