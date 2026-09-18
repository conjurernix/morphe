(ns snake-event.loop
  (:refer-clojure :exclude [run!])
  (:require [morphe.adapters.quil :as quil]
            [morphe.core :as game]
            [quil.core :as q]
            [snake-event.board :as board]
            [snake-event.food :as food]
            [snake-event.game :as snake-game]
            [snake-event.snake :as snake]))

(defn input-events
  [_game-state pressed-keys]
  (let [direction (some (fn [[key-set direction]]
                          (when (some pressed-keys key-set) direction))
                        [[#{:up :w} :up]
                         [#{:down :s} :down]
                         [#{:left :a} :left]
                         [#{:right :d} :right]])]
    (cond-> []
      direction (conj (game/event snake/id [::snake/turn direction]))
      (contains? pressed-keys :r)
      (into [(game/event snake/id [::snake/restart])
             (game/event food/id [::food/restart snake/id
                                  board/initial-snake-cells])]))))

(defn tick-source
  [_context _dt]
  [(game/event snake/id [::snake/tick])])

(defn- board-size
  [{:keys [columns rows]} cell-size]
  [(* columns cell-size) (* rows cell-size)])

(defn overlay!
  [game-state _pressed-keys _effects]
  (let [{:keys [phase]} (snake-game/snake-state game-state)
        [width height] (board-size board/grid board/cell-size)]
    (q/fill 255 255 255)
    (q/text-align :left :top)
    (q/text-size 18)
    (q/text (str "Score: " (snake-game/score game-state)) 12 12)
    (when (not= phase :playing)
      (q/text-align :center :center)
      (q/text-size 32)
      (q/text (if (= phase :won) "You win!" "Game over") (/ width 2.0) (/ height 2.0))
      (q/text-size 18)
      (q/text "Press R to restart" (/ width 2.0) (+ (/ height 2.0) 42)))))

(defn run!
  []
  (let [[width height] (board-size board/grid board/cell-size)]
    (quil/start!
      {:game-state (snake-game/initial-game)
       :sources [tick-source]
       :width width
       :height height
       :title "Morphe Snake"
       :background [20 24 30]
       :frame-rate 10.0
       :render-context {:cell-size board/cell-size}
       :input-fn input-events
       :effect-handlers {:sound/play (fn [sound] (println "Effect:" sound))}
       :overlay-fn overlay!})))
