(ns platformer.world
  (:require [morphe.collision :as collision]))

(def width 960)
(def height 540)
(def player-size [32 40])
(def spawn-position {:x 100 :y 440})

(def platforms
  (mapv (fn [index platform]
          (assoc platform :id [:platform index]))
        (range)
        [{:x 480 :y 500 :width 960 :height 80}
         {:x 200 :y 390 :width 220 :height 24}
         {:x 560 :y 330 :width 220 :height 24}
         {:x 820 :y 230 :width 180 :height 24}]))

(def platform-index
  (collision/create-index 128 platforms))

(def hazards
  [{:x 390 :y 468 :width 56 :height 24}
   {:x 720 :y 468 :width 56 :height 24}])

(def coin-position [560 290])

(def enemy-specs
  {:enemy-one {:x 280 :y 440 :patrol-min 210 :patrol-max 340 :speed 70.0}
   :enemy-two {:x 600 :y 440 :patrol-min 520 :patrol-max 670 :speed 85.0}})

(def context
  {:platform-index platform-index
   :hazards hazards
   :coin-position coin-position
   :width width
   :height height})
