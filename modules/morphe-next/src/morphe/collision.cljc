(ns morphe.collision
  "Pure AABB collision queries backed by a uniform spatial grid.")

(def ^:private index-version 1)

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :collision))))

(defn- finite-number?
  [value]
  (and (number? value)
       #?(:clj (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(defn- aabb?
  [{:keys [x y width height]}]
  (and (every? finite-number? [x y width height])
       (pos? width)
       (pos? height)))

(defn- validate-aabb!
  [value label]
  (when-not (aabb? value)
    (fail! "AABBs require finite x, y, width, and height values"
           {:label label :value value}))
  value)

(defn- bounds
  [{:keys [x y width height]}]
  {:left (- x (/ width 2.0))
   :right (+ x (/ width 2.0))
   :top (- y (/ height 2.0))
   :bottom (+ y (/ height 2.0))})

(defn overlaps?
  "Returns whether two center-based AABBs overlap with positive area."
  [left right]
  (let [{left-left :left left-right :right left-top :top left-bottom :bottom}
        (bounds (validate-aabb! left :left))
        {right-left :left right-right :right right-top :top right-bottom :bottom}
        (bounds (validate-aabb! right :right))]
    (and (< left-left right-right)
         (> left-right right-left)
         (< left-top right-bottom)
         (> left-bottom right-top))))

(defn contains-point?
  "Returns whether a point map with :x and :y lies inside or on an AABB."
  [aabb {:keys [x y] :as point}]
  (validate-aabb! aabb :aabb)
  (when-not (and (finite-number? x) (finite-number? y))
    (fail! "Points require finite x and y values" {:value point}))
  (let [{:keys [left right top bottom]} (bounds aabb)]
    (and (<= left x right)
         (<= top y bottom))))

(defn- collider?
  [{:keys [id] :as collider}]
  (and (some? id) (aabb? collider)))

(defn- validate-colliders!
  [colliders]
  (when-not (sequential? colliders)
    (fail! "Colliders must be sequential" {:value colliders}))
  (when-not (every? collider? colliders)
    (fail! "Colliders require a non-nil :id and a valid AABB"
           {:value colliders}))
  (let [ids (mapv :id colliders)]
    (when-not (= (count ids) (count (set ids)))
      (fail! "Collider IDs must be unique" {:ids ids})))
  (vec colliders))

(defn- cell-range
  [minimum maximum cell-size]
  (range (long (Math/floor (/ minimum cell-size)))
         (inc (long (Math/floor (/ maximum cell-size))))))

(defn- cell-coordinates
  [cell-size {:keys [left right top bottom]}]
  (for [cell-x (cell-range left right cell-size)
        cell-y (cell-range top bottom cell-size)]
    [cell-x cell-y]))

(defn create-index
  "Indexes colliders by grid cell. Every collider needs a unique :id and AABB keys."
  [cell-size colliders]
  (when-not (and (finite-number? cell-size) (pos? cell-size))
    (fail! "Spatial-index cell size must be a positive finite number"
           {:cell-size cell-size}))
  (let [entries (validate-colliders! colliders)
        cells (reduce (fn [index {:keys [id] :as collider}]
                        (reduce (fn [next-index coordinate]
                                  (update next-index coordinate (fnil conj []) id))
                                index
                                (cell-coordinates cell-size (bounds collider))))
                      {}
                      entries)]
    {:morphe.collision/version index-version
     :cell-size cell-size
     :entries (into {} (map (juxt :id identity) entries))
     :order (mapv :id entries)
     :cells cells}))

(defn- validate-index!
  [{version :morphe.collision/version
    :keys [cell-size entries order cells]
    :as index}]
  (when-not (and (= index-version version)
                 (finite-number? cell-size)
                 (pos? cell-size)
                 (map? entries)
                 (vector? order)
                 (map? cells)
                 (= (set order) (set (keys entries)))
                 (every? collider? (vals entries))
                 (every? vector? (vals cells)))
    (fail! "Spatial index is malformed" {:value index}))
  index)

(defn- candidate-entries
  [index query-bounds]
  (let [{:keys [cell-size cells entries order]} (validate-index! index)
        ids (into #{}
                  (mapcat #(get cells % []))
                  (cell-coordinates cell-size query-bounds))]
    (into []
          (keep #(when (contains? ids %) (get entries %)))
          order)))

(defn query
  "Returns indexed colliders that overlap an AABB in their insertion order."
  [index aabb]
  (validate-aabb! aabb :query)
  (into []
        (filter #(overlaps? aabb %))
        (candidate-entries index (bounds aabb))))

(defn- ray-axis-interval
  [origin direction minimum maximum]
  (if (zero? direction)
    (when (<= minimum origin maximum)
      [#?(:clj Double/NEGATIVE_INFINITY
          :cljs js/Number.NEGATIVE_INFINITY)
       #?(:clj Double/POSITIVE_INFINITY
          :cljs js/Number.POSITIVE_INFINITY)])
    (let [first-distance (/ (- minimum origin) direction)
          second-distance (/ (- maximum origin) direction)]
      [(min first-distance second-distance)
       (max first-distance second-distance)])))

(defn- ray-distance
  [{origin-x :x origin-y :y} {direction-x :x direction-y :y} aabb max-distance]
  (let [{:keys [left right top bottom]} (bounds aabb)
        [x-entry x-exit] (ray-axis-interval origin-x direction-x left right)
        [y-entry y-exit] (ray-axis-interval origin-y direction-y top bottom)]
    (when (and x-entry y-entry)
      (let [entry (max x-entry y-entry)
            exit (min x-exit y-exit)]
        (when (and (<= entry exit)
                   (>= exit 0.0)
                   (<= entry max-distance))
          (max 0.0 entry))))))

(defn raycast
  "Returns ray hits through the index, nearest first.

  Direction is measured in world units per distance unit. Results include
  :collider, :distance, and :point."
  [index origin direction max-distance]
  (when-not (and (every? finite-number? [(:x origin) (:y origin)
                                         (:x direction) (:y direction)
                                         max-distance])
                 (pos? max-distance)
                 (not (and (zero? (:x direction)) (zero? (:y direction)))))
    (fail! "Rays require finite origin, direction, and positive distance"
           {:origin origin :direction direction :max-distance max-distance}))
  (let [end-x (+ (:x origin) (* (:x direction) max-distance))
        end-y (+ (:y origin) (* (:y direction) max-distance))
        query-bounds {:left (min (:x origin) end-x)
                      :right (max (:x origin) end-x)
                      :top (min (:y origin) end-y)
                      :bottom (max (:y origin) end-y)}]
    (->> (candidate-entries index query-bounds)
         (keep (fn [collider]
                 (when-let [distance (ray-distance origin direction collider max-distance)]
                   {:collider collider
                    :distance distance
                    :point {:x (+ (:x origin) (* (:x direction) distance))
                            :y (+ (:y origin) (* (:y direction) distance))}})))
         (sort-by :distance)
         vec)))

(defn first-hit
  "Returns the nearest `raycast` hit, or nil when the ray misses."
  [index origin direction max-distance]
  (first (raycast index origin direction max-distance)))
