(ns platformer.main
  (:require [morphe.adapters.lwjgl :as lwjgl]
            [morphe.core :as core]
            [platformer.assets :as assets]
            [platformer.entities.player :as player]
            [platformer.game :as game]
            [platformer.sound :as sound]
            [platformer.world :as world]))

(defn input-events
  [_game-state pressed-keys key-events]
  (let [active (into pressed-keys key-events)]
    {:events
     [(core/event player/id
                  [::player/input {:left (boolean (some #{:left :a} active))
                                   :right (boolean (some #{:right :d} active))
                                   :jump (boolean (some #{:up :w} key-events))
                                   :shoot (contains? active :space)}])]
     :controls (cond-> []
                 (some #{:escape} key-events) (conj [:app/quit]))}))

(defn handle-application-event
  [state _game-state {:keys [type focused?]}]
  {:app state
   :events []
   :controls (case type
               :morphe.app/focus-changed
               [[(if focused? :app/resume :app/pause)]]
               [])})

(defn -main
  [& _]
  (let [asset-paths (assets/write-sprites! "target/platformer-assets")]
    (try
      (sound/start!)
      (lwjgl/start!
       {:game-state (game/initial-game)
        :sources [game/tick-source]
        :width world/width
        :height world/height
        :title "Morphe Skybound"
        :background [17 23 42]
        :assets asset-paths
        :frame-rate 60.0
        :input-fn input-events
        :app-event-fn handle-application-event
        :render-context {}
        :effect-handlers {:sound/play sound/play-tone!}})
      (finally
        (sound/stop!)))))
