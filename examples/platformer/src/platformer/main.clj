(ns platformer.main
  "Quil entry point for the platformer example.

  This namespace owns the window-facing callbacks. The game rules remain in
  `platformer.game` so they can run in tests without a graphics window."
  (:require [morphe.adapters.quil :as quil]
            [morphe.core :as ecs]
            [platformer.game :as game]
            [quil.core :as q]))

(defn draw-text! [value x y size color]
  "Draw one overlay label using the current Quil text coordinate system."
  (q/fill (first color) (second color) (nth color 2))
  (q/text-size size)
  (q/text value x y))

(defn overlay! [world _pressed-keys _events]
  "Render score, controls, and the message shown after a finished run."
  (let [{:keys [phase score total-coins]} (ecs/get-resource world game/state-key)]
    (q/text-align :left :top)
    (draw-text! (str "Coins: " score "/" total-coins) 18 16 20 [255 255 255])
    (draw-text! "Move: arrows or A/D   Jump: W, Up, or Space" 18 45 15 [230 240 255])
    (when (not= phase :playing)
      (q/text-align :center :center)
      (draw-text! (if (= phase :won) "You win!" "Game over") (/ game/width 2.0) 220 44
                  (if (= phase :won) [84 205 124] [255 110 110]))
      (draw-text! "Press R to restart" (/ game/width 2.0) 280 22 [255 255 255]))))

(defn -main [& _]
  "Start the platformer window with the initial world and runtime."
  (quil/start!
    {:runtime game/runtime
     :world (game/initial-world)
     :width game/width
     :height game/height
     :title "Morphe Platformer"
     :background [91 177 235]
     :command-fn (fn [_world pressed-keys]
                    [(game/input-command pressed-keys)])
     :overlay-fn overlay!}))
