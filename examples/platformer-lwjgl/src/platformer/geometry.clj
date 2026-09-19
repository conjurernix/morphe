(ns platformer.geometry
  (:require [morphe.collision :as collision]))

(defn clamp
  [value minimum maximum]
  (max minimum (min maximum value)))

(defn- platform-top
  [{:keys [y height]}]
  (- y (/ height 2)))

(defn- sweep-box
  [{:keys [x y]} velocity dt [width height]]
  (let [next-y (+ y (* velocity dt))]
    {:x x
     :y (/ (+ y next-y) 2.0)
     :width width
     :height (+ height (Math/abs (- next-y y)))}))

(defn find-landing
  [position velocity dt platform-index player-size]
  (let [{:keys [y]} position
        [player-width player-height] player-size
        next-bottom (+ y (/ player-height 2) (* velocity dt))
        current-bottom (+ y (/ player-height 2))
        platforms (collision/query platform-index
                                   (sweep-box position velocity dt player-size))]
    (some (fn [{:keys [x width] :as platform}]
            (let [top (platform-top platform)]
              (when (and (pos? velocity)
                         (<= current-bottom top)
                         (>= next-bottom top)
                         (< (- x (/ width 2)) (+ (:x position) (/ player-width 2)))
                         (> (+ x (/ width 2)) (- (:x position) (/ player-width 2))))
                (assoc platform :landing-y (- top (/ player-height 2))))))
          platforms)))

(defn find-support
  [position platform-index [player-width player-height]]
  (let [bottom (+ (:y position) (/ player-height 2))]
    (some (fn [{:keys [x width] :as platform}]
            (let [top (platform-top platform)]
              (when (and (< (Math/abs (- bottom top)) 0.5)
                         (< (- x (/ width 2)) (+ (:x position) (/ player-width 2)))
                         (> (+ x (/ width 2)) (- (:x position) (/ player-width 2))))
                (assoc platform :landing-y (- top (/ player-height 2))))))
          (collision/query platform-index
                           {:x (:x position)
                            :y (:y position)
                            :width player-width
                            :height (inc player-height)}))))
