(ns platformer.entities.hazards
  (:require [morphe.core :as game]
            [platformer.world :as world]))

(def id :hazards)
(def entity-type ::hazards)

(defn- update-state
  [state _context _message]
  [state []])

(defn- render-hazard
  [{:keys [x y width height]}]
  (cons {:kind :rectangle
         :x x
         :y y
         :width width
         :height height
         :fill [224 78 92]
         :layer 1}
        (map (fn [surface-x]
               {:kind :circle
                :x surface-x
                :y (- y (/ height 2))
                :radius 7
                :fill [255 174 62]
                :layer 2})
             (range (- x (/ width 2) -8) (+ x (/ width 2)) 16))))

(defn render
  [_state _context _render-context]
  (mapcat render-hazard world/hazards))

(defn create-entity
  [state]
  (game/typed-entity entity-type state [] update-state render))

(defn create
  []
  [id (create-entity {})])
