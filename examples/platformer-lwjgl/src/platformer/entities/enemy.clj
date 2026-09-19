(ns platformer.entities.enemy
  (:require [morphe.collision :as collision]
            [morphe.core :as game]
            [morphe.fixed-step :as fixed-step]
            [platformer.entities.player :as player]
            [platformer.geometry :as geometry]
            [platformer.particles :as particles]
            [platformer.world :as world]))

(def ids [:enemy-one :enemy-two])
(def entity-type ::enemy)

(defn- move
  [{:keys [x direction patrol-min patrol-max speed] :as state} dt]
  (let [next-x (+ x (* direction speed dt))
        reached-boundary? (or (<= next-x patrol-min) (>= next-x patrol-max))]
    (cond-> (assoc state :x (geometry/clamp next-x patrol-min patrol-max))
      reached-boundary? (update :direction -))))

(defn update-state
  [{:keys [x y defeated? particles] :as state} _context [message-type & values]]
  (case message-type
    :platformer.game/tick
    (let [dt (first values)
          moved-state (if defeated? state (move state dt))]
      [(assoc moved-state :particles (particles/update-all particles dt)) []])

    ::projectile-hit
    (let [[source-projectile projectile-x projectile-y] values
          hit? (and (not defeated?)
                    (collision/overlaps?
                     {:x projectile-x :y projectile-y :width 12 :height 12}
                     {:x x :y y :width 32 :height 32}))]
      [(if hit?
         (assoc state
                :defeated? true
                :particles (particles/create-burst x y [255 174 62] 12))
         state)
       (cond-> []
         hit? (conj (game/send source-projectile
                                [:platformer.entities.projectile/consume]))
         hit? (conj (game/effect [:sound/play :enemy-hit])))])

    ::player-at
    (let [[source-player player-x player-y] values
          hit? (and (not defeated?)
                    (collision/overlaps?
                     {:x player-x :y player-y
                      :width (first world/player-size)
                      :height (second world/player-size)}
                     {:x x :y y :width 32 :height 32}))]
      [state
       (if hit?
         [(game/send source-player [::player/enemy-impact])]
         [])])

    [state []]))

(defn render
  [{:keys [x y defeated? particles]} _context render-context]
  (let [previous (fixed-step/previous-entity-state render-context)
        alpha (get render-context :morphe.render/alpha 1.0)
        render-x (fixed-step/interpolate-number (:x previous) x alpha)
        render-y (fixed-step/interpolate-number (:y previous) y alpha)]
    (concat
      (if defeated?
        []
        [{:kind :sprite :asset :enemy
          :x render-x :y render-y :width 32 :height 32 :layer 2}])
      (particles/render particles 3))))

(defn create-entity
  [state]
  (game/typed-entity entity-type state [] update-state render))

(defn create
  [enemy-id]
  [enemy-id (create-entity
              (merge (world/enemy-specs enemy-id)
                     {:direction 1 :defeated? false :particles []}))])

(defn create-all
  []
  (map create ids))
