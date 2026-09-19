(ns platformer.entities.platforms
  (:require [morphe.core :as game]
            [platformer.world :as world]))

(def id :platforms)
(def entity-type ::platforms)

(defn- update-state
  [state _context _message]
  [state []])

(defn render
  [_state _context _render-context]
  (map (fn [{:keys [x y width height]}]
         {:kind :rectangle
          :x x
          :y y
          :width width
          :height height
          :fill [48 59 92]
          :layer 0})
       world/platforms))

(defn create-entity
  [state]
  (game/typed-entity entity-type state [] update-state render))

(defn create
  []
  [id (create-entity {})])
