(ns morphe.components.2d)

(def position-key ::position)
(def velocity-key ::velocity)
(def acceleration-key ::acceleration)
(def scale-key ::scale)
(def rotation-key ::rotation)
(def transform-key ::transform)

(def x ::x)
(def y ::y)
(def radians ::radians)

(defn position [x-value y-value] {x x-value y y-value})
(defn velocity [x-value y-value] {x x-value y y-value})
(defn acceleration [x-value y-value] {x x-value y y-value})
(defn scale [x-value y-value] {x x-value y y-value})
(defn rotation [radians-value] {radians radians-value})
(defn transform [position-value rotation-value scale-value]
  {:position position-value :rotation rotation-value :scale scale-value})
