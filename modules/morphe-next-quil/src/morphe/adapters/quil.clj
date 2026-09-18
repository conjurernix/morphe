(ns morphe.adapters.quil
  (:require [morphe.core :as game]
            [quil.core :as q]))

(defn press-key
  [state key]
  (update state :pressed-keys conj key))

(defn release-key
  [state key]
  (update state :pressed-keys disj key))

(defn resolve-asset
  [assets asset-key]
  (or (get assets asset-key)
      (throw (ex-info "No loaded Quil asset matches the sprite key"
                      {:asset asset-key}))))

(defn rectangle
  [x y width height fill]
  {:kind :rectangle :x x :y y :width width :height height :fill fill})

(defn circle
  [x y radius fill]
  {:kind :circle :x x :y y :radius radius :fill fill})

(defn sprite
  [asset x y & {:keys [width height tint]}]
  (cond-> {:kind :sprite :asset asset :x x :y y}
    width (assoc :width width)
    height (assoc :height height)
    tint (assoc :tint tint)))

(defn- load-assets
  [asset-paths]
  (reduce-kv (fn [assets asset-key path]
               (assoc assets asset-key (q/load-image path)))
             {}
             asset-paths))

(defn- apply-fill!
  [color]
  (apply q/fill (or color [255 255 255])))

(defn- draw-renderable!
  [assets {:keys [kind x y radius width height fill asset tint]}]
  (case kind
    :circle
    (do
      (apply-fill! fill)
      (q/no-stroke)
      (q/ellipse x y (* 2 radius) (* 2 radius)))

    :rectangle
    (do
      (apply-fill! fill)
      (q/no-stroke)
      (q/rect x y width height))

    :sprite
    (let [image (resolve-asset assets asset)]
      (when tint
        (apply q/tint tint))
      (if (and width height)
        (q/image image x y width height)
        (q/image image x y))
      (when tint
        (q/no-tint)))

    (throw (ex-info "Unsupported Quil renderable" {:kind kind}))))

(defn render-game!
  [game-state assets render-context]
  (q/rect-mode :center)
  (q/image-mode :center)
  (doseq [renderable (sort-by (fn [{:keys [layer]}] (or layer 0))
                              (game/render-data game-state render-context))]
    (draw-renderable! assets renderable)))

(defn start!
  "Runs a Quil loop over immutable game state.
  :input-fn returns events and :effect-handlers executes effects."
  [{:keys [game-state sources width height title background assets frame-rate input-fn
           render-context overlay-fn event-fn effect-handlers]
    :or {width 800
         height 600
         title "Morphe"
         background [0 0 0]
         assets {}
         sources []
         frame-rate 60.0
         input-fn (fn [_ _] [])
         render-context {}
         effect-handlers {}}}]
  (when-not (fn? input-fn)
    (throw (ex-info "Quil adapter :input-fn must be a function" {})))
  (let [state (atom {:game game-state :pressed-keys #{} :assets {} :last-time nil})]
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
              (let [{game-state :game :keys [pressed-keys assets last-time]} @state
                    current-time (q/millis)
                    dt (if last-time
                         (/ (- current-time last-time) 1000.0)
                         (/ 1.0 frame-rate))
                    {next-game :game :keys [effects] :as result}
                    (game/step game-state sources dt
                               (input-fn game-state pressed-keys))]
                (apply q/background background)
                (render-game! next-game assets render-context)
                (when overlay-fn
                  (overlay-fn next-game pressed-keys effects))
                (when (seq effects)
                  (game/dispatch-effects! effect-handlers effects))
                (swap! state assoc :game next-game :last-time current-time)
                (when event-fn
                  (event-fn result)))))))
