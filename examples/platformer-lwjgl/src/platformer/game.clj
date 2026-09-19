(ns platformer.game
  (:require [morphe.core :as game]
            [platformer.entities.coin :as coin]
            [platformer.entities.enemy :as enemy]
            [platformer.entities.hazards :as hazards]
            [platformer.entities.platforms :as platforms]
            [platformer.entities.player :as player]
            [platformer.entities.projectile :as projectile]
            [platformer.world :as world]))

(def factories
  {coin/entity-type coin/create-entity
   enemy/entity-type enemy/create-entity
   hazards/entity-type hazards/create-entity
   platforms/entity-type platforms/create-entity
   player/entity-type player/create-entity
   projectile/entity-type projectile/create-entity})

(defn tick-source
  [_context dt]
  [(game/event [::tick dt])])

(defn initial-game
  []
  (reduce (fn [game-state [entity-id entity]]
            (game/add-entity game-state entity-id entity))
          (game/game world/context)
          (concat [(platforms/create)
                   (hazards/create)
                   (player/create)
                   (coin/create)]
                  (enemy/create-all))))
