(ns morphe.components.3d)

(def position-key ::position)
(def velocity-key ::velocity)
(def acceleration-key ::acceleration)
(def scale-key ::scale)
(def rotation-key ::rotation)
(def transform-key ::transform)

(def x ::x)
(def y ::y)
(def z ::z)
(def quaternion-x ::quaternion-x)
(def quaternion-y ::quaternion-y)
(def quaternion-z ::quaternion-z)
(def quaternion-w ::quaternion-w)

(defn position [x-value y-value z-value] {x x-value y y-value z z-value})
(defn velocity [x-value y-value z-value] {x x-value y y-value z z-value})
(defn acceleration [x-value y-value z-value] {x x-value y y-value z z-value})
(defn scale [x-value y-value z-value] {x x-value y y-value z z-value})
(defn rotation [x-value y-value z-value w-value]
  {quaternion-x x-value quaternion-y y-value quaternion-z z-value quaternion-w w-value})
(defn transform [position-value rotation-value scale-value]
  {:position position-value :rotation rotation-value :scale scale-value})
