(ns platformer.entities.player
  (:require [morphe.collision :as collision]
            [morphe.core :as game]
            [morphe.fixed-step :as fixed-step]
            [platformer.entities.coin :as coin]
            [platformer.entities.projectile :as projectile]
            [platformer.geometry :as geometry]
            [platformer.particles :as particles]
            [platformer.world :as world]))

(def id :player)
(def entity-type ::player)

(def initial-input
  {:left false :right false :jump false :shoot false})

(defn- player-box
  [{:keys [x y]}]
  {:x x
   :y y
   :width (first world/player-size)
   :height (second world/player-size)})

(defn- tick
  [{:keys [x y vy grounded input particles coin-collected? shoot-cooldown
           projectile-sequence] :as state}
   {:keys [platform-index hazards coin-position width height]} [dt]]
  (let [{:keys [left right jump shoot]} input
        direction (- (if right 1 0) (if left 1 0))
        next-vx (* direction 230.0)
        jump? (and jump grounded)
        next-vy (if jump? -470.0 (+ vy (* 1050.0 dt)))
        player-width (first world/player-size)
        player-height (second world/player-size)
        next-x (geometry/clamp (+ x (* next-vx dt))
                               (/ player-width 2)
                               (- width (/ player-width 2)))
        landing-platform (geometry/find-landing {:x next-x :y y}
                                                next-vy dt platform-index world/player-size)
        next-y (+ y (* next-vy dt))
        standing-platform (when (and grounded (not jump?))
                            (geometry/find-support {:x next-x :y y}
                                                   platform-index world/player-size))
        landed? (and (not grounded) (some? landing-platform))
        supported? (or landed? (some? standing-platform))
        support (or landing-platform standing-platform)
        final-y (if supported? (:landing-y support) next-y)
        next-player (player-box {:x next-x :y final-y})
        coin-box {:x (first coin-position) :y (second coin-position)
                  :width 24 :height 24}
        collect? (and (not coin-collected?) (collision/overlaps? next-player coin-box))
        hazard? (some #(collision/overlaps? next-player %) hazards)
        fallen? (> final-y (+ height player-height))
        reset? (or hazard? fallen?)
        next-shoot-cooldown (max 0.0 (- shoot-cooldown dt))
        fire? (and shoot (zero? next-shoot-cooldown))
        projectile-entry (when fire?
                           (projectile/create projectile-sequence
                                              (:x next-player)
                                              (:y next-player)
                                              (if (neg? (:facing state)) -1 1)))
        dust (when (or jump? landed?)
               (mapv #(particles/create x (+ y 20) %
                                        (if jump? [70 214 190] [255 174 62]))
                     (range 8)))
        impact (when reset?
                 (particles/create-burst (:x next-player) (:y next-player)
                                         [255 91 43] 14))]
    [(cond-> (assoc state
                    :x (:x next-player)
                    :y final-y
                    :vx next-vx
                    :vy (if supported? 0.0 next-vy)
                    :grounded supported?
                    :input (assoc input :jump false)
                    :shoot-cooldown (if fire? 0.2 next-shoot-cooldown)
                    :projectile-sequence (if fire?
                                           (inc projectile-sequence)
                                           projectile-sequence)
                    :particles (if reset?
                                 impact
                                 (into (particles/update-all particles dt) dust)))
       collect? (assoc :coin-collected? true)
       reset? (merge world/spawn-position
                     {:vx 0.0 :vy 0.0 :grounded true
                      :particles impact
                      :input (assoc input :jump false)})
       (not= direction 0) (assoc :facing direction))
     (cond-> []
       (not reset?) (conj (game/broadcast
                           [:platformer.entities.enemy/player-at id
                            (:x next-player) (:y next-player)]))
       jump? (conj (game/effect [:sound/play :jump]))
       landed? (conj (game/effect [:sound/play :land]))
       reset? (conj (game/effect [:sound/play :hit]))
       fire? (conj (apply game/spawn projectile-entry))
       fire? (conj (game/effect [:sound/play :shoot]))
       collect? (conj (game/effect [:sound/play :coin])
                      (game/send coin/id [::coin/collect])))]))

(defn update-state
  [state context [message-type & values]]
  (case message-type
    ::input [(assoc state :input (first values)) []]
    :platformer.game/tick (tick state context values)
    ::enemy-impact
    [(merge state world/spawn-position
            {:vx 0.0
             :vy 0.0
             :grounded true
             :shoot-cooldown 0.0
             :input initial-input
             :particles (particles/create-burst (:x state) (:y state)
                                                 [178 104 255] 14)})
     [(game/effect [:sound/play :hit])]]
    [state []]))

(defn render
  [{:keys [x y particles]} _context render-context]
  (let [previous (fixed-step/previous-entity-state render-context)
        alpha (get render-context :morphe.render/alpha 1.0)
        render-x (fixed-step/interpolate-number (:x previous) x alpha)
        render-y (fixed-step/interpolate-number (:y previous) y alpha)]
    (concat
      [{:kind :sprite :asset :player
        :x render-x :y render-y :width 32 :height 40 :layer 2}]
      (particles/render particles 1))))

(defn create-entity
  [state]
  (game/typed-entity entity-type state [] update-state render))

(defn create
  []
  [id (create-entity
        (merge world/spawn-position
               {:vx 0.0
                :vy 0.0
                :grounded true
                :facing 1
                :input initial-input
                :shoot-cooldown 0.0
                :projectile-sequence 0
                :particles []
                :coin-collected? false}))])
