(ns platformer.entities.projectile
  (:require [morphe.core :as game]
            [morphe.fixed-step :as fixed-step]))

(def entity-type ::projectile)

(defn update-state
  [{:keys [id x y direction] :as state} {:keys [width]}
   [message-type & values]]
  (case message-type
    :platformer.game/tick
    (let [next-x (+ x (* direction 500.0 (first values)))
          active-in-world? (and (> next-x -20) (< next-x (+ width 20)))]
      [(assoc state :x next-x)
       (if active-in-world?
         [(game/broadcast [:platformer.entities.enemy/projectile-hit id next-x y])]
         [(game/despawn id)])])

    ::consume
    [state [(game/despawn id)]]

    [state []]))

(defn render
  [{:keys [x y]} _context render-context]
  (let [previous (fixed-step/previous-entity-state render-context)
        alpha (get render-context :morphe.render/alpha 1.0)]
    [{:kind :sprite
      :asset :projectile
      :x (fixed-step/interpolate-number (:x previous) x alpha)
      :y (fixed-step/interpolate-number (:y previous) y alpha)
      :width 12
      :height 12
      :layer 3}]))

(defn create-entity
  [state]
  (game/typed-entity entity-type state [] update-state render))

(defn create
  [sequence-number x y direction]
  (let [id [:projectile sequence-number]]
    [id (create-entity {:id id :x x :y y :direction direction})]))
