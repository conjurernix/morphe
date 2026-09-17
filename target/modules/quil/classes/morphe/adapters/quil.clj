(ns morphe.adapters.quil
  (:require [morphe.components.2d :as c2d]
            [morphe.components.render-2d :as render-2d]
            [morphe.core :as ecs]
            [morphe.query :as query]
            [quil.core :as q]))

(def shape-key render-2d/shape-key)
(def sprite-key render-2d/sprite-key)
(def circle render-2d/circle)
(def rectangle render-2d/rectangle)
(def sprite render-2d/sprite)

(defn press-key
  [state key]
  (update state :pressed-keys conj key))

(defn release-key
  [state key]
  (update state :pressed-keys disj key))

(defn drawable-entities
  [world]
  (let [drawables
        (keep
          (fn [entity]
            (let [shape (ecs/component world entity shape-key)
                  sprite-value (ecs/component world entity sprite-key)]
              (when (or shape sprite-value)
                {:entity entity
                 :position (ecs/component world entity c2d/position-key)
                 :scale (ecs/component world entity c2d/scale-key)
                 :rotation (ecs/component world entity c2d/rotation-key)
                 :shape shape
                 :sprite sprite-value
                 :layer (or (:layer shape) (:layer sprite-value) 0)})))
          (query/query world (query/all c2d/position-key)))]
    (->> drawables
         (sort-by (juxt :layer :entity))
         vec)))

(defn resolve-asset
  [assets asset-key]
  (or (get assets asset-key)
      (throw (ex-info "No loaded Quil asset matches the sprite key"
                      {:asset asset-key}))))

(defn- load-assets
  [asset-paths]
  (reduce-kv
    (fn [assets asset-key path]
      (assoc assets asset-key (q/load-image path)))
    {}
    asset-paths))

(defn- apply-fill
  [color]
  (apply q/fill (or color [255 255 255])))

(defn- draw-shape!
  [{:keys [kind radius width height fill]}]
  (apply-fill fill)
  (q/no-stroke)
  (case kind
    :circle (q/ellipse 0 0 (* 2 radius) (* 2 radius))
    :rectangle (q/rect 0 0 width height)
    (throw (ex-info "Unsupported Quil shape kind" {:kind kind}))))

(defn- draw-sprite!
  [assets {:keys [asset width height tint]}]
  (let [image (resolve-asset assets asset)]
    (when tint
      (apply q/tint tint))
    (if (and width height)
      (q/image image 0 0 width height)
      (q/image image 0 0))
    (when tint
      (q/no-tint))))

(defn render-world!
  [world assets]
  (q/rect-mode :center)
  (q/image-mode :center)
  (doseq [{:keys [position scale rotation shape sprite]} (drawable-entities world)]
    (let [scale-x (get scale c2d/x 1.0)
          scale-y (get scale c2d/y 1.0)
          radians (get rotation c2d/radians 0.0)]
      (q/push-matrix)
      (q/translate (get position c2d/x) (get position c2d/y))
      (q/rotate radians)
      (q/scale scale-x scale-y)
      (when shape
        (draw-shape! shape))
      (when sprite
        (draw-sprite! assets sprite))
      (q/pop-matrix))))

(defn start!
  [{:keys [runtime world width height title background assets frame-rate command-fn event-fn overlay-fn]
    :or {width 800
         height 600
         title "Morphe"
         background [0 0 0]
         assets {}
         frame-rate 60.0
         command-fn (fn [_world _pressed-keys] [])}}]
  (when-not (fn? command-fn)
    (throw (ex-info "Quil adapter :command-fn must be a function" {})))
  (when (and event-fn (not (fn? event-fn)))
    (throw (ex-info "Quil adapter :event-fn must be a function" {})))
  (when (and overlay-fn (not (fn? overlay-fn)))
    (throw (ex-info "Quil adapter :overlay-fn must be a function" {})))
  (let [state (atom {:world world :pressed-keys #{} :assets {} :last-time nil})]
    (q/sketch
      :title title
      :size [width height]
      :features [:exit-on-close]
      :setup (fn []
               (q/frame-rate frame-rate)
               (swap! state assoc :assets (load-assets assets)))
      :key-pressed (fn []
                     (swap! state press-key (q/key-as-keyword)))
      :key-released (fn []
                      (swap! state release-key (q/key-as-keyword)))
      :draw (fn []
              (let [{:keys [world pressed-keys assets last-time]} @state
                    current-time (q/millis)
                    dt (if last-time
                         (/ (- current-time last-time) 1000.0)
                         (/ 1.0 frame-rate))
                    commands (command-fn world pressed-keys)
                    result (ecs/step runtime world dt commands)]
                (apply q/background background)
                (render-world! (:world result) assets)
                (when overlay-fn
                  (overlay-fn (:world result) pressed-keys (:events result)))
                (swap! state assoc :world (:world result) :last-time current-time)
                (when event-fn
                  (event-fn (:events result))))))))
