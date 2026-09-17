(ns platformer.game
  "The platformer simulation, expressed as immutable Morphe world transformations.

  Quil input enters through `input-command`. The runtime then applies input,
  movement, enemy patrol, collection, outcome, and restart systems in order."
  (:require [morphe.adapters.quil :as quil]
            [morphe.components.2d :as c2d]
            [morphe.core :as ecs]
            [morphe.query :as query]
            [morphe.system :as system]))

(def player-key ::player)
(def platform-key ::platform)
(def coin-key ::coin)
(def enemy-key ::enemy)
(def goal-key ::goal)
(def bounds-key ::bounds)
(def grounded-key ::grounded)
(def input-key ::input)
(def state-key ::state)

;; Positions use screen coordinates: x grows right and y grows down.
(def width 960.0)
(def height 540.0)
(def player-speed 230.0)
(def jump-speed 470.0)
(def gravity 1200.0)

(defn bounds [width-value height-value]
  "Return the collision dimensions for a centered rectangle in world units."
  {:width width-value :height height-value})

(defn rect [x-value y-value width-value height-value fill]
  "Build the shared components for a centered, drawable rectangle.

  Bounds stay separate from the Quil shape so gameplay systems can perform
  collision checks without knowing how the entity is rendered."
  {c2d/position-key (c2d/position x-value y-value)
   bounds-key (bounds width-value height-value)
   quil/shape-key (quil/rectangle width-value height-value fill)})

(defn center-rect [world x-value y-value width-value height-value fill component-key]
  "Spawn a rectangle with one marker component and return the next world.

  The entity identifier is intentionally discarded because level assembly only
  needs the resulting world."
  (first (ecs/spawn world (assoc (rect x-value y-value width-value height-value fill)
                                 component-key true))))

(defn spawn-platforms [world]
  "Add the floor and elevated platforms that define the level layout."
  (reduce
    (fn [current-world [x-value y-value width-value height-value]]
      (center-rect current-world x-value y-value width-value height-value [113 78 48] platform-key))
    world
    [[480.0 510.0 960.0 60.0]
     [170.0 405.0 220.0 28.0]
     [490.0 340.0 190.0 28.0]
     [790.0 425.0 220.0 28.0]
     [775.0 270.0 150.0 28.0]]))

(defn spawn-coins [world]
  "Add the collectible coins at their fixed level positions."
  (reduce
    (fn [current-world [x-value y-value]]
      (center-rect current-world x-value y-value 22.0 22.0 [255 211 64] coin-key))
    world
    [[170.0 365.0] [490.0 300.0] [790.0 385.0] [775.0 230.0]]))

(defn initial-world []
  "Create a fresh playable world with all level entities and shared state.

  A restart calls this function so restarting is a replacement of immutable
  state, rather than a sequence of cleanup mutations."
  (let [[world player]
        (ecs/spawn (ecs/world)
                   (assoc (rect 90.0 450.0 34.0 46.0 [44 117 255])
                          player-key true
                          c2d/velocity-key (c2d/velocity 0.0 0.0)
                          grounded-key false))
        [world _enemy]
        (ecs/spawn world
                   (assoc (rect 620.0 465.0 38.0 38.0 [220 56 56])
                          enemy-key {:min-x 560.0 :max-x 720.0 :direction 1.0}
                          c2d/velocity-key (c2d/velocity 75.0 0.0)))
        world (spawn-platforms world)
        world (spawn-coins world)
        [world _goal]
        (ecs/spawn world
                   (assoc (rect 895.0 455.0 28.0 80.0 [84 205 124]) goal-key true))]
    (-> world
        (ecs/set-resource state-key {:phase :playing :score 0 :total-coins 4})
        (ecs/set-resource input-key #{}))))

(defn entity-rect [world entity]
  "Convert an entity's centered position and bounds into edge coordinates."
  (let [position (ecs/component world entity c2d/position-key)
        {:keys [width height]} (ecs/component world entity bounds-key)]
    {:left (- (get position c2d/x) (/ width 2.0))
     :right (+ (get position c2d/x) (/ width 2.0))
     :top (- (get position c2d/y) (/ height 2.0))
     :bottom (+ (get position c2d/y) (/ height 2.0))}))

(defn intersects? [first-rect second-rect]
  "Return true when two axis-aligned rectangles overlap with positive area."
  (and (< (:left first-rect) (:right second-rect))
       (> (:right first-rect) (:left second-rect))
       (< (:top first-rect) (:bottom second-rect))
       (> (:bottom first-rect) (:top second-rect))))

(defn platform-landing [player-old player-new platforms]
  "Return the top edge of the first platform the player crossed while falling.

  Checking both old and new rectangles prevents a player moving upward through
  a platform from being treated as landed."
  (some
    (fn [platform]
      (when (and (>= (:bottom player-new) (:top platform))
                 (<= (:bottom player-old) (:top platform))
                 (< (:left player-new) (:right platform))
                 (> (:right player-new) (:left platform)))
        (:top platform)))
    platforms))

(defn movement-input [pressed-keys]
  "Normalize raw Quil key names into simulation actions.

  Gameplay systems consume these action keywords instead of knowing the
  keyboard layout."
  (cond-> #{}
    (some pressed-keys #{:left :a}) (conj :left)
    (some pressed-keys #{:right :d}) (conj :right)
    (some pressed-keys #{:up :w :space}) (conj :jump)
    (contains? pressed-keys :r) (conj :restart)))

(defn input-command [pressed-keys]
  "Build the command that carries the current held-key actions into the ECS."
  (ecs/command :platformer/input {:input (movement-input pressed-keys)}))

(def input-system
  "Store the normalized input command as a resource for later systems."
  (system/command-system
    {:name :platformer/input
     :command-types #{:platformer/input}}
    (fn [world command _dt]
      (ecs/set-resource world input-key (:input command)))))

(defn player-step [world player dt]
  "Advance the player for one frame using seconds as the time unit.

  Horizontal speed is direct control. Vertical speed uses gravity, with a jump
  replacing the vertical velocity only while the player is grounded."
  (let [input (ecs/get-resource world input-key #{})
        position (ecs/component world player c2d/position-key)
        velocity (ecs/component world player c2d/velocity-key)
        player-bounds (ecs/component world player bounds-key)
        grounded (ecs/component world player grounded-key)
        direction (- (if (contains? input :right) 1.0 0.0)
                     (if (contains? input :left) 1.0 0.0))
        jump? (and (contains? input :jump) grounded)
        next-velocity (c2d/velocity (* direction player-speed)
                                     (if jump? (- jump-speed)
                                         (+ (get velocity c2d/y) (* gravity dt))))
        next-position (c2d/position (+ (get position c2d/x) (* (get next-velocity c2d/x) dt))
                                     (+ (get position c2d/y) (* (get next-velocity c2d/y) dt)))
        old-rect (entity-rect world player)
        new-rect (assoc old-rect
                        :left (- (get next-position c2d/x) (/ (:width player-bounds) 2.0))
                        :right (+ (get next-position c2d/x) (/ (:width player-bounds) 2.0))
                        :top (- (get next-position c2d/y) (/ (:height player-bounds) 2.0))
                        :bottom (+ (get next-position c2d/y) (/ (:height player-bounds) 2.0)))
        platforms (map #(entity-rect world %) (query/query world (query/all platform-key bounds-key c2d/position-key)))
        landing-top (when (pos? (get next-velocity c2d/y))
                      (platform-landing old-rect new-rect platforms))
        landed-position (if landing-top
                          (c2d/position (get next-position c2d/x)
                                        (- landing-top (/ (:height player-bounds) 2.0)))
                          next-position)
        landed-velocity (if landing-top
                          (c2d/velocity (get next-velocity c2d/x) 0.0)
                          next-velocity)]
    (-> world
        (ecs/set-component player c2d/position-key landed-position)
        (ecs/set-component player c2d/velocity-key landed-velocity)
        (ecs/set-component player grounded-key (boolean landing-top)))))

(def player-system
  "Apply movement and platform landing to the player entity."
  (system/entity-system
    {:name :platformer/player
     :query (query/all player-key c2d/position-key c2d/velocity-key bounds-key grounded-key)}
    player-step))

(defn move-enemy [world enemy dt]
  "Move an enemy between its configured horizontal patrol limits."
  (let [enemy-data (ecs/component world enemy enemy-key)
        position (ecs/component world enemy c2d/position-key)
        direction (:direction enemy-data)
        next-x (+ (get position c2d/x) (* direction 75.0 dt))
        at-edge (or (>= next-x (:max-x enemy-data)) (<= next-x (:min-x enemy-data)))
        next-direction (if at-edge (- direction) direction)]
    (-> world
        (ecs/set-component enemy c2d/position-key
                           (c2d/position (max (:min-x enemy-data) (min (:max-x enemy-data) next-x))
                                         (get position c2d/y)))
        (ecs/set-component enemy enemy-key (assoc enemy-data :direction next-direction)))))

(def enemy-system
  "Advance every entity marked as an enemy."
  (system/entity-system
    {:name :platformer/enemy
     :query (query/all enemy-key c2d/position-key)}
    move-enemy))

(defn player-entity [world]
  "Return the world's player entity identifier."
  (first (query/query world (query/all player-key bounds-key c2d/position-key))))

(defn collect-coins [world]
  "Destroy coins intersecting the player and increment the score once per coin."
  (let [player (player-entity world)
        player-rect (entity-rect world player)]
    (reduce
      (fn [current-world coin]
        (if (intersects? player-rect (entity-rect current-world coin))
          (-> current-world
              (ecs/destroy coin)
              (ecs/update-resource state-key update :score inc))
          current-world))
      world
      (query/query world (query/all coin-key bounds-key c2d/position-key)))))

(def collection-system
  "Apply coin collection after movement has updated positions."
  (system/system {:name :platformer/collect :operation (fn [world _dt] (collect-coins world))}))

(defn update-outcome [world]
  "Set the phase to `:lost` or `:won` when the player reaches an outcome."
  (let [state (ecs/get-resource world state-key)
        player (player-entity world)
        player-rect (entity-rect world player)
        enemy-hit? (some #(intersects? player-rect (entity-rect world %))
                         (query/query world (query/all enemy-key bounds-key c2d/position-key)))
        goal-reached? (some #(intersects? player-rect (entity-rect world %))
                            (query/query world (query/all goal-key bounds-key c2d/position-key)))
        fell? (> (:bottom player-rect) height)
        phase (cond
                (or enemy-hit? fell?) :lost
                goal-reached? :won
                :else (:phase state))]
    (ecs/set-resource world state-key (assoc state :phase phase))))

(def outcome-system
  "Evaluate enemy contact, falling, and goal contact after movement."
  (system/system {:name :platformer/outcome :operation (fn [world _dt] (update-outcome world))}))

(defn restart-system [world _dt]
  "Replace a finished world with a fresh one when restart is held."
  (let [input (ecs/get-resource world input-key #{})
        phase (:phase (ecs/get-resource world state-key))]
    (if (and (not= phase :playing) (contains? input :restart))
      (initial-world)
      world)))

(def runtime
  "The ordered command and gameplay systems used by the Quil loop."
  {:command-systems [input-system]
   :systems [player-system enemy-system collection-system outcome-system
             (system/system {:name :platformer/restart :operation restart-system})]})
