(ns snake-event.board)

(def grid {:columns 24 :rows 18})
(def cell-size 24.0)
(def initial-snake-cells [[10 8] [9 8] [8 8]])
(def directions {:up [0 -1] :down [0 1] :left [-1 0] :right [1 0]})
(def opposites {:up :down :down :up :left :right :right :left})

(defn add-cell [cell direction]
  (mapv + cell (directions direction)))

(defn inside-grid? [[x y]]
  (let [{:keys [columns rows]} grid]
    (and (<= 0 x) (< x columns)
         (<= 0 y) (< y rows))))

(defn free-cells
  [occupied]
  (let [{:keys [columns rows]} grid
        occupied (set occupied)]
    (vec (for [y (range rows)
               x (range columns)
               :let [cell [x y]]
               :when (not (occupied cell))]
           cell))))

(defn choose-free-cell
  [occupied choose-cell]
  (let [available (free-cells occupied)]
    (when (seq available)
      (choose-cell available))))
