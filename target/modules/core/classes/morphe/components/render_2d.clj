(ns morphe.components.render-2d)

(def shape-key ::shape)
(def sprite-key ::sprite)

(defn circle
  [radius fill]
  {:kind :circle :radius radius :fill fill})

(defn rectangle
  [width height fill]
  {:kind :rectangle :width width :height height :fill fill})

(defn sprite
  [asset]
  {:asset asset})
