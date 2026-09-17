(ns lwjgl-example.main
  (:require [morphe.adapters.lwjgl :as lwjgl]
            [morphe.components.2d :as c2d]
            [morphe.components.3d :as c3d]
            [morphe.components.render-2d :as render-2d]
            [morphe.components.render-3d :as render-3d]
            [morphe.core :as ecs]))

(defn initial-world []
  (let [[world _]
        (ecs/spawn (ecs/world)
                   {c2d/position-key (c2d/position 260.0 220.0)
                    render-2d/shape-key (render-2d/rectangle 180.0 100.0 [44 117 255])})
        [world _]
        (ecs/spawn world
                   {c2d/position-key (c2d/position 540.0 380.0)
                    render-2d/shape-key (render-2d/circle 55.0 [255 211 64])})]
    (first (ecs/spawn world
                     {c3d/position-key (c3d/position 0.0 0.0 -6.0)
                      render-3d/cube-key (render-3d/cube 2.0 [84 205 124])}))))

(defn -main [& _]
  (lwjgl/start! {:runtime []
                 :world (initial-world)
                 :width 800
                 :height 600
                 :title "Morphe LWJGL 2D + 3D"}))
