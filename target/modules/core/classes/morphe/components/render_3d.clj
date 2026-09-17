(ns morphe.components.render-3d)

(def cube-key ::cube)
(def camera-key ::camera)

(defn cube
  ([size color]
   (cube size color 0))
  ([size color layer]
   {:size size :color color :layer layer}))

(defn camera
  [options]
  (merge {:position [0.0 0.0 0.0]
          :rotation [0.0 0.0 0.0 1.0]
          :fov 60.0
          :near 0.1
          :far 100.0}
         options))
