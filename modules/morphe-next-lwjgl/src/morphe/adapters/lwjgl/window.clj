(ns morphe.adapters.lwjgl.window
  "GLFW window, display, clipboard, and presentation controls."
  (:import [org.lwjgl.glfw GLFW]
           [org.lwjgl.system MemoryStack MemoryUtil]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :window))))

(defn options
  "Validates and normalizes the :window options accepted by lwjgl/start!."
  [value]
  (if-not (map? value)
    (fail! "Window options must be a map" {:value value})
    (do
      (when-not (every? #{:fullscreen? :vsync? :resizable?} (keys value))
        (fail! "Window options contain an unsupported key" {:value value}))
      (let [{:keys [fullscreen? vsync? resizable?]
             :or {fullscreen? false vsync? true resizable? true}} value]
        (when-not (every? boolean? [fullscreen? vsync? resizable?])
          (fail! "Window options :fullscreen?, :vsync?, and :resizable? must be boolean"
                 {:value value}))
        {:fullscreen? fullscreen? :vsync? vsync? :resizable? resizable?}))))

(defn- primary-monitor!
  []
  (let [monitor (GLFW/glfwGetPrimaryMonitor)]
    (when (zero? monitor)
      (fail! "Unable to find a primary display" {}))
    monitor))

(defn- video-mode!
  [monitor]
  (or (GLFW/glfwGetVideoMode monitor)
      (fail! "Unable to read display video mode" {:monitor monitor})))

(defn- video-mode-data
  [mode]
  {:width (.width mode)
   :height (.height mode)
   :refresh-rate (.refreshRate mode)
   :red-bits (.redBits mode)
   :green-bits (.greenBits mode)
   :blue-bits (.blueBits mode)})

(declare content-scale)

(defn create!
  "Initializes GLFW and creates a visible window from normalized options."
  [{:keys [width height title fullscreen? vsync? resizable?]}]
  (when-not (and (pos-int? width) (pos-int? height) (string? title))
    (fail! "Window dimensions must be positive integers and title must be a string"
           {:width width :height height :title title}))
  (when-not (GLFW/glfwInit)
    (fail! "Unable to initialize GLFW" {}))
  (try
    (GLFW/glfwDefaultWindowHints)
    (GLFW/glfwWindowHint GLFW/GLFW_RESIZABLE
                         (if resizable? GLFW/GLFW_TRUE GLFW/GLFW_FALSE))
    (let [monitor (when fullscreen? (primary-monitor!))
          mode (when monitor (video-mode! monitor))
          actual-width (if mode (.width mode) width)
          actual-height (if mode (.height mode) height)
          window (GLFW/glfwCreateWindow actual-width actual-height title
                                        (or monitor MemoryUtil/NULL) MemoryUtil/NULL)]
      (when (zero? window)
        (fail! "Unable to create GLFW window"
               {:width actual-width :height actual-height :fullscreen? fullscreen?}))
      (try
        (GLFW/glfwMakeContextCurrent window)
        (GLFW/glfwSwapInterval (if vsync? 1 0))
        (GLFW/glfwShowWindow window)
        {:handle window
         :width actual-width
         :height actual-height
         :content-scale (content-scale window)
         :state (atom {:handle window
                       :fullscreen? fullscreen?
                       :vsync? vsync?
                       :windowed-bounds {:x 0 :y 0 :width width :height height}})}
        (catch Throwable cause
          (GLFW/glfwDestroyWindow window)
          (throw cause))))
    (catch Throwable cause
      (GLFW/glfwTerminate)
      (throw cause))))

(defn content-scale
  "Returns the window's content scale as positive numeric x and y values."
  [window]
  (with-open [stack (MemoryStack/stackPush)]
    (let [x-scale (.mallocFloat stack 1)
          y-scale (.mallocFloat stack 1)]
      (GLFW/glfwGetWindowContentScale window x-scale y-scale)
      {:x (double (.get x-scale 0))
       :y (double (.get y-scale 0))})))

(defn- window-bounds
  [window]
  (with-open [stack (MemoryStack/stackPush)]
    (let [x (.mallocInt stack 1)
          y (.mallocInt stack 1)
          width (.mallocInt stack 1)
          height (.mallocInt stack 1)]
      (GLFW/glfwGetWindowPos window x y)
      (GLFW/glfwGetWindowSize window width height)
      {:x (.get x 0) :y (.get y 0)
       :width (.get width 0) :height (.get height 0)})))

(defn set-fullscreen!
  "Switches a window state between fullscreen and its saved windowed bounds."
  [state enabled?]
  (when-not (boolean? enabled?)
    (fail! "Fullscreen state must be boolean" {:value enabled?}))
  (let [{:keys [handle fullscreen? windowed-bounds]} @state]
    (when (not= fullscreen? enabled?)
      (if enabled?
        (let [windowed-bounds (window-bounds handle)
              monitor (primary-monitor!)
              mode (video-mode! monitor)]
          (GLFW/glfwSetWindowMonitor handle monitor 0 0
                                     (.width mode) (.height mode) (.refreshRate mode))
          (swap! state assoc :fullscreen? true :windowed-bounds windowed-bounds))
        (let [{:keys [x y width height]} windowed-bounds]
          (GLFW/glfwSetWindowMonitor handle MemoryUtil/NULL x y width height 0)
          (swap! state assoc :fullscreen? false)))))
  nil)

(defn set-vsync!
  "Enables or disables presentation synchronization for the current context."
  [state enabled?]
  (when-not (boolean? enabled?)
    (fail! "VSync state must be boolean" {:value enabled?}))
  (GLFW/glfwSwapInterval (if enabled? 1 0))
  (swap! state assoc :vsync? enabled?)
  nil)

(defn set-clipboard!
  "Copies text to the GLFW clipboard for a window."
  [state text]
  (when-not (string? text)
    (fail! "Clipboard text must be a string" {:value text}))
  (GLFW/glfwSetClipboardString (:handle @state) text)
  nil)

(defn clipboard
  "Returns clipboard text, or nil when no text is available."
  [state]
  (GLFW/glfwGetClipboardString (:handle @state)))

(defn- monitor-data
  [monitor primary?]
  (with-open [stack (MemoryStack/stackPush)]
    (let [x (.mallocInt stack 1)
          y (.mallocInt stack 1)
          width-mm (.mallocInt stack 1)
          height-mm (.mallocInt stack 1)
          x-scale (.mallocFloat stack 1)
          y-scale (.mallocFloat stack 1)]
      (GLFW/glfwGetMonitorPos monitor x y)
      (GLFW/glfwGetMonitorPhysicalSize monitor width-mm height-mm)
      (GLFW/glfwGetMonitorContentScale monitor x-scale y-scale)
      {:name (GLFW/glfwGetMonitorName monitor)
       :primary? primary?
       :position {:x (.get x 0) :y (.get y 0)}
       :physical-size-mm {:width (.get width-mm 0) :height (.get height-mm 0)}
       :content-scale {:x (double (.get x-scale 0))
                       :y (double (.get y-scale 0))}
       :video-mode (video-mode-data (video-mode! monitor))})))

(defn display-info
  "Returns stable data for every connected display."
  []
  (let [primary-monitor (primary-monitor!)
        monitors (GLFW/glfwGetMonitors)]
    (when (nil? monitors)
      (fail! "Unable to enumerate displays" {}))
    {:displays (mapv (fn [index]
                       (let [monitor (.get monitors index)]
                         (monitor-data monitor (= monitor primary-monitor))))
                     (range (.position monitors) (.limit monitors)))}))
