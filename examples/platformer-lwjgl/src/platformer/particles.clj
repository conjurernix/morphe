(ns platformer.particles)

(def lifetime 0.45)

(defn create
  [x y index color]
  {:x x
   :y y
   :vx (- (* (mod index 5) 25.0) 50.0)
   :vy (- (* (mod index 4) 18.0) 70.0)
   :life lifetime
   :color color})

(defn create-burst
  [x y color count]
  (mapv #(create x y % color) (range count)))

(defn update-all
  [particles dt]
  (->> particles
       (map (fn [{:keys [x y vx vy life color]}]
              {:x (+ x (* vx dt))
               :y (+ y (* vy dt))
               :vx vx
               :vy (+ vy 180.0)
               :life (- life dt)
               :color color}))
       (filter #(pos? (:life %)))
       vec))

(defn render
  [particles layer]
  (map (fn [{:keys [x y life color]}]
         {:kind :circle
          :x x
          :y y
          :radius (* 5.0 (/ life lifetime))
          :fill color
          :layer layer})
       particles))
