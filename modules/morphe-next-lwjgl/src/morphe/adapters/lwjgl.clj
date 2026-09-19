(ns morphe.adapters.lwjgl
  "LWJGL application loop for Morphe game state and render data."
  (:require [morphe.adapters.lwjgl.assets :as assets]
            [morphe.adapters.lwjgl.audio :as audio]
            [morphe.adapters.lwjgl.input :as input]
            [morphe.adapters.lwjgl.renderer :as renderer]
            [morphe.adapters.lwjgl.window :as lwjgl-window]
            [morphe.application :as application]
            [morphe.core :as game]
            [morphe.fixed-step :as fixed-step])
  (:import [org.lwjgl.glfw Callbacks GLFW GLFWFramebufferSizeCallbackI
            GLFWWindowCloseCallbackI GLFWWindowContentScaleCallbackI
            GLFWWindowFocusCallbackI]
           [org.lwjgl.opengl GL GL11]))

(defn press-key
  [state key]
  (-> state
      (update :pressed-keys conj key)
      (update :key-events (fnil conj []) key)))

(defn release-key
  [state key]
  (update state :pressed-keys disj key))

(defn color
  "Converts RGB values in byte or normalized form to normalized RGB."
  [value]
  (vec (take 3 (renderer/normalize-color value))))

(defn rectangle
  [x y width height fill]
  {:kind :rectangle :x x :y y :width width :height height :fill fill})

(defn circle
  [x y radius fill]
  {:kind :circle :x x :y y :radius radius :fill fill})

(defn line
  [from to width fill]
  {:kind :line :from from :to to :width width :fill fill})

(defn polygon
  [points fill]
  {:kind :polygon :points points :fill fill})

(defn clip-rect
  "Creates a viewport-space clip rectangle in integer pixels."
  [x y width height]
  [x y width height])

(defn text
  [font value x y fill]
  {:kind :text :font font :text value :x x :y y :fill fill})

(defn camera
  "Creates a camera whose position maps to the render viewport's top-left corner."
  [x y & {:keys [zoom] :or {zoom 1.0}}]
  {:x x :y y :zoom zoom})

(defn sprite
  [asset x y & {:keys [width height tint region]}]
  (cond-> {:kind :sprite :asset asset :x x :y y}
    width (assoc :width width)
    height (assoc :height height)
    tint (assoc :tint tint)
    region (assoc :region region)))

(defn resolve-asset
  [loaded-assets asset-key]
  (assets/get-asset loaded-assets asset-key))

(defn render-game!
  "Compiles and draws one game's render data through a reusable renderer."
  [renderer-state width height game-state loaded-assets render-context]
  (->> (game/render-data game-state render-context)
       (renderer/compile-batches loaded-assets (:morphe.render/camera render-context))
       (#(renderer/render! renderer-state width height % loaded-assets))))

(defn- initialize-window!
  [options]
  (let [created (lwjgl-window/create! options)]
    (try
      (GL/createCapabilities)
      created
      (catch Throwable cause
        (GLFW/glfwDestroyWindow (:handle created))
        (GLFW/glfwTerminate)
        (throw cause)))))

(defn- configure-viewport!
  [width height]
  (GL11/glViewport 0 0 width height))

(defn- clear!
  [background]
  (apply GL11/glClearColor (map float (conj (color background) 1.0)))
  (GL11/glClear GL11/GL_COLOR_BUFFER_BIT))

(defn normalize-input-result
  "Normalizes event sequences or input maps containing :events and :controls."
  [result]
  (cond
    (sequential? result)
    {:events (vec result) :controls []}

    (map? result)
    (let [{:keys [events controls] :or {events [] controls []}} result]
      (when-not (and (sequential? events) (sequential? controls))
        (throw (ex-info "LWJGL input maps require sequential :events and :controls"
                        {:phase :input :value result})))
      {:events (vec events) :controls (vec controls)})

    :else
    (throw (ex-info "LWJGL input callbacks must return events or an input map"
                    {:phase :input :value result}))))

(defn- install-application-callbacks!
  [window event-queue]
  (GLFW/glfwSetWindowCloseCallback
    window
    (reify GLFWWindowCloseCallbackI
      (invoke [_ _window]
        (swap! event-queue conj (application/quit-requested)))))
  (GLFW/glfwSetWindowFocusCallback
    window
    (reify GLFWWindowFocusCallbackI
      (invoke [_ _window focused?]
        (swap! event-queue conj (application/focus-changed focused?)))))
  (GLFW/glfwSetFramebufferSizeCallback
    window
    (reify GLFWFramebufferSizeCallbackI
      (invoke [_ _window width height]
        (when (and (pos? width) (pos? height))
          (swap! event-queue conj (application/resized width height))))))
  (GLFW/glfwSetWindowContentScaleCallback
    window
    (reify GLFWWindowContentScaleCallbackI
      (invoke [_ _window x-scale y-scale]
        (swap! event-queue conj
               (application/content-scale-changed x-scale y-scale)))))
  nil)

(defn- take-events!
  [event-queue]
  (first (swap-vals! event-queue (constantly []))))

(defn- resize-viewport!
  [previous-application next-application]
  (let [previous-size ((juxt :width :height) previous-application)
        next-size ((juxt :width :height) next-application)]
    (when-not (= previous-size next-size)
      (apply configure-viewport! next-size))))

(defn- paused-frame
  [loop-state application-events entering-pause?]
  (let [reset-loop (if entering-pause?
                     (fixed-step/reset-timing loop-state)
                     loop-state)]
    {:loop (fixed-step/queue-events reset-loop application-events)
     :effects []
     :updates 0
     :alpha 0.0
     :dropped-seconds 0.0
     :ticks []}))

(defn- create-audio!
  [{:keys [sounds music groups volume max-voices]
    :or {sounds {} music {} groups {} volume 1.0 max-voices 32}}]
  (let [audio-state (audio/create! sounds {:max-voices max-voices
                                           :music music :groups groups})]
    (audio/set-volume! audio-state volume)
    audio-state))

(defn- audio-effect-handlers
  [audio-state]
  (if-not audio-state
    {}
    {:audio/play (fn
                   ([sound-key]
                    (audio/play! audio-state sound-key))
                   ([sound-key options]
                    (audio/play! audio-state sound-key options)))
     :audio/stop (fn [] (audio/stop! audio-state))
     :audio/volume (fn [volume] (audio/set-volume! audio-state volume))
     :audio/group-volume (fn [group volume]
                           (audio/set-group-volume! audio-state group volume))
     :audio/music-play (fn
                         ([music-key] (audio/play-music! audio-state music-key))
                         ([music-key options]
                          (audio/play-music! audio-state music-key options)))
     :audio/music-stop (fn [] (audio/stop-music! audio-state))
     :audio/music-seek (fn [seconds] (audio/seek-music! audio-state seconds))}))

(defn- window-effect-handlers
  [window-state]
  {:window/fullscreen (fn [enabled?]
                        (lwjgl-window/set-fullscreen! window-state enabled?))
   :window/vsync (fn [enabled?]
                   (lwjgl-window/set-vsync! window-state enabled?))
   :window/clipboard-set (fn [text]
                           (lwjgl-window/set-clipboard! window-state text))})

(defn- update-audio-pause!
  [audio-state previous-application next-application]
  (when audio-state
    (cond
      (and (not (:paused? previous-application))
           (:paused? next-application))
      (audio/pause! audio-state)

      (and (:paused? previous-application)
           (not (:paused? next-application)))
      (audio/resume! audio-state)))
  nil)

(defn- run-cleanups!
  [[cleanup & remaining]]
  (when cleanup
    (try
      (cleanup)
      (finally
        (run-cleanups! remaining)))))

(defn- destroy-runtime!
  [window renderer-state loaded-assets audio-state]
  (run-cleanups!
    [(fn [] (when audio-state (audio/destroy! audio-state)))
     (fn [] (assets/destroy-assets! loaded-assets))
     (fn [] (when renderer-state (renderer/destroy! renderer-state)))
     input/destroy-global-callbacks!
     (fn [] (Callbacks/glfwFreeCallbacks window))
     (fn [] (GLFW/glfwDestroyWindow window))
     #(GLFW/glfwTerminate)]))

(defn start!
  "Runs a GLFW loop over immutable game state.

  :input-fn receives game state, held keys, and key presses.
  :input-state-fn receives game state and a complete input frame.
  Either callback returns events or {:events [...] :controls [...]}."
  [{:keys [game-state sources width height title background assets audio window
           frame-rate input-fn input-state-fn update-hz max-catch-up-updates
           max-frame-seconds render-context overlay-fn event-fn effect-handlers
           app-state app-event-fn error-fn]
    :or {width 800 height 600 title "Morphe" background [0 0 0] assets {}
         sources [] frame-rate 60.0 input-fn (fn [_ _ _] []) render-context {}
         effect-handlers {} app-state {}}}]
  (when-not (and (fn? input-fn)
                 (or (nil? input-state-fn) (fn? input-state-fn)))
    (throw (ex-info "LWJGL input callbacks must be functions"
                    {:phase :input})))
  (let [window-options (lwjgl-window/options (or window {}))
        initialized-window (initialize-window!
                             (merge {:width width :height height :title title}
                                    window-options))
        window-handle (:handle initialized-window)
        window-state (:state initialized-window)
        renderer-state (atom nil)
        loaded-assets (atom {})
        audio-state (atom nil)
        application-events (atom [])
        input-state (input/create-state)]
    (try
      (configure-viewport! (:width initialized-window) (:height initialized-window))
      (install-application-callbacks! window-handle application-events)
      (input/install-callbacks! window-handle input-state)
      (reset! renderer-state (renderer/create!))
      (reset! loaded-assets (assets/load-assets! assets))
      (when audio
        (reset! audio-state (create-audio! audio)))
      (let [active-effect-handlers
            (merge (window-effect-handlers window-state)
                   (audio-effect-handlers @audio-state) effect-handlers)]
        (loop [loop-state (fixed-step/create
                          game-state
                          {:update-hz (or update-hz (long frame-rate))
                           :max-catch-up-updates (or max-catch-up-updates 5)
                           :max-frame-seconds (or max-frame-seconds 0.25)})
             application-state (application/create
                                 {:state app-state
                                  :width (:width initialized-window)
                                  :height (:height initialized-window)
                                  :content-scale (:content-scale initialized-window)})
             last-time (GLFW/glfwGetTime)]
        (when (:running? application-state)
          (GLFW/glfwPollEvents)
          (let [input-frame (input/take-frame! input-state)
                keys (:keys input-frame)
                key-events (input/pressed-key-events input-frame)
                current-time (GLFW/glfwGetTime)
                elapsed-seconds (if (pos? last-time)
                                  (- current-time last-time)
                                  (/ 1.0 frame-rate))
                current-game (:game loop-state)
                application-result
                (application/handle-events application-state current-game
                                           (take-events! application-events)
                                           app-event-fn)
                after-events (:application application-result)
                window-input {:fullscreen? (:fullscreen? @window-state)
                              :vsync? (:vsync? @window-state)
                              :clipboard (lwjgl-window/clipboard window-state)}
                complete-input-frame (assoc input-frame :window window-input)
                input-result
                (if (:running? after-events)
                  (normalize-input-result
                    (if input-state-fn
                      (input-state-fn current-game complete-input-frame)
                      (input-fn current-game keys key-events)))
                  {:events [] :controls []})
                next-application (application/apply-controls
                                   after-events (:controls input-result))
                entering-pause? (and (not (:paused? application-state))
                                     (:paused? next-application))
                application-game-events (:events application-result)
                gameplay-events (if (:paused? next-application)
                                  []
                                  (:events input-result))
                frame-result
                (cond
                  (not (:running? next-application))
                  {:loop loop-state :effects [] :updates 0 :alpha 0.0
                   :dropped-seconds 0.0 :ticks []}

                  (:paused? next-application)
                  (paused-frame loop-state application-game-events
                                entering-pause?)

                  :else
                  (fixed-step/advance loop-state sources elapsed-seconds
                                      (into application-game-events
                                            gameplay-events)))
                next-loop (:loop frame-result)
                next-game (:game next-loop)
                next-render-context
                (assoc render-context
                       :morphe.render/alpha (:alpha frame-result)
                       :morphe.render/previous-game (:previous-game next-loop))]
            (update-audio-pause! @audio-state application-state
                                 next-application)
            (when @audio-state
              (audio/update! @audio-state))
            (GLFW/glfwSetWindowShouldClose window-handle
                                           (not (:running? next-application)))
            (when (:running? next-application)
              (resize-viewport! application-state next-application)
              (clear! background)
              (render-game! @renderer-state
                            (:width next-application)
                            (:height next-application)
                            next-game @loaded-assets next-render-context)
              (when overlay-fn
                (overlay-fn next-game keys (:effects frame-result)
                            elapsed-seconds))
              (when (seq (:effects frame-result))
                (game/dispatch-effects! active-effect-handlers
                                        (:effects frame-result)))
              (GLFW/glfwSwapBuffers window-handle)
              (when event-fn
                (event-fn (assoc frame-result
                                 :game next-game
                                 :application next-application
                                 :input complete-input-frame)))
              (recur next-loop next-application current-time))))))
      (catch Throwable cause
        (case (application/error-action
                error-fn cause
                (merge {:phase :adapter} (ex-data cause)))
          :close nil
          :rethrow (throw cause)))
      (finally
        (destroy-runtime! window-handle @renderer-state @loaded-assets @audio-state)))))
