(ns morphe.adapters.lwjgl.input
  "Stable GLFW keyboard, text, mouse, and gamepad input data."
  (:import [org.lwjgl.glfw GLFW GLFWCharCallbackI GLFWCursorPosCallbackI
            GLFWGamepadState GLFWJoystickCallbackI GLFWKeyCallbackI
            GLFWMouseButtonCallbackI GLFWScrollCallbackI]
           [org.lwjgl.system MemoryStack]))

(defn- special-keys
  []
  {-1 :unknown, 32 :space, 39 :apostrophe, 44 :comma, 45 :minus
   46 :period, 47 :slash, 59 :semicolon, 61 :equal, 91 :left-bracket
   92 :backslash, 93 :right-bracket, 96 :grave-accent
   161 :world-1, 162 :world-2
   256 :escape, 257 :enter, 258 :tab, 259 :backspace, 260 :insert
   261 :delete, 262 :right, 263 :left, 264 :down, 265 :up
   266 :page-up, 267 :page-down, 268 :home, 269 :end
   280 :caps-lock, 281 :scroll-lock, 282 :num-lock
   283 :print-screen, 284 :pause
   330 :keypad-decimal, 331 :keypad-divide, 332 :keypad-multiply
   333 :keypad-subtract, 334 :keypad-add, 335 :keypad-enter
   336 :keypad-equal
   340 :left-shift, 341 :left-control, 342 :left-alt, 343 :left-super
   344 :right-shift, 345 :right-control, 346 :right-alt, 347 :right-super
   348 :menu})

(defn- create-key-names
  []
  (merge (special-keys)
         (into {} (map (fn [code]
                         [code (keyword (str (char code)))])
                       (range 48 58)))
         (into {} (map (fn [code]
                         [code (keyword (str (char (+ (int \a)
                                                     (- code 65)))))])
                       (range 65 91)))
         (into {} (map-indexed (fn [index code]
                                 [code (keyword (str "f" (inc index)))])
                               (range 290 315)))
         (into {} (map-indexed (fn [index code]
                                 [code (keyword (str "keypad-" index))])
                               (range 320 330)))))

(def ^:private key-names
  (delay (create-key-names)))

(defn- create-mouse-button-names
  []
  {0 :left, 1 :right, 2 :middle, 3 :button-4, 4 :button-5
   5 :button-6, 6 :button-7, 7 :button-8})

(def ^:private mouse-button-names
  (delay (create-mouse-button-names)))

(def ^:private gamepad-axis-names
  [:left-x :left-y :right-x :right-y :left-trigger :right-trigger])

(def ^:private gamepad-button-names
  [:a :b :x :y :left-bumper :right-bumper :back :start :guide
   :left-stick :right-stick :dpad-up :dpad-right :dpad-down :dpad-left])

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :input))))

(defn key-name
  "Returns a stable keyword for every GLFW key code."
  [key-code]
  (when-not (integer? key-code)
    (fail! "GLFW key codes must be integers" {:value key-code}))
  (or (@key-names key-code)
      (keyword "key" (str key-code))))

(defn modifiers
  "Converts GLFW modifier bits into stable keywords."
  [mods]
  (when-not (integer? mods)
    (fail! "GLFW modifier flags must be an integer" {:value mods}))
  (cond-> #{}
    (not (zero? (bit-and mods 1))) (conj :shift)
    (not (zero? (bit-and mods 2))) (conj :control)
    (not (zero? (bit-and mods 4))) (conj :alt)
    (not (zero? (bit-and mods 8))) (conj :super)
    (not (zero? (bit-and mods 16))) (conj :caps-lock)
    (not (zero? (bit-and mods 32))) (conj :num-lock)))

(defn create-state
  "Creates mutable adapter input state around immutable frame data."
  []
  (atom {:keys #{}
         :mouse-buttons #{}
         :cursor {:x 0.0 :y 0.0}
         :devices {}
         :events []}))

(defn- queue-event
  [state update-state event]
  (swap! state
         (fn [input-state]
           (-> (update-state input-state)
               (update :events conj event)))))

(defn- update-key
  [state key-code scancode action mods]
  (let [key (key-name key-code)
        action-name (case action
                      0 :released
                      1 :pressed
                      2 :repeated)
        event {:type (keyword "key" (name action-name))
               :key key
               :key-code key-code
               :scancode scancode
               :modifiers (modifiers mods)}]
    (queue-event state
                 (fn [input-state]
                   (case action
                     0 (update input-state :keys disj key)
                     1 (update input-state :keys conj key)
                     2 input-state))
                 event)))

(defn- update-mouse-button
  [state button action mods]
  (let [button-name (or (@mouse-button-names button)
                        (keyword "mouse-button" (str button)))
        pressed? (= action GLFW/GLFW_PRESS)]
    (queue-event
      state
      #(update % :mouse-buttons
               (if pressed? conj disj)
               button-name)
      {:type (if pressed? :mouse/pressed :mouse/released)
       :button button-name
       :button-code button
       :modifiers (modifiers mods)})))

(defn- update-joystick
  [state joystick-id event]
  (if (= event GLFW/GLFW_CONNECTED)
    (let [device-type (if (GLFW/glfwJoystickIsGamepad joystick-id)
                        :gamepad
                        :joystick)]
      (queue-event state
                   #(assoc-in % [:devices joystick-id] device-type)
                   {:type (keyword (name device-type) "connected")
                    :id joystick-id}))
    (let [device-type (get-in @state [:devices joystick-id] :joystick)]
      (queue-event state
                   #(update % :devices dissoc joystick-id)
                   {:type (keyword (name device-type) "disconnected")
                    :id joystick-id}))))

(defn install-callbacks!
  "Installs GLFW input callbacks that append stable event maps to state."
  [window state]
  (GLFW/glfwSetKeyCallback
    window
    (reify GLFWKeyCallbackI
      (invoke [_ _window key-code scancode action mods]
        (update-key state key-code scancode action mods))))
  (GLFW/glfwSetCharCallback
    window
    (reify GLFWCharCallbackI
      (invoke [_ _window codepoint]
        (queue-event state identity
                     {:type :text/input
                      :text (String. (Character/toChars codepoint))
                      :codepoint codepoint}))))
  (GLFW/glfwSetMouseButtonCallback
    window
    (reify GLFWMouseButtonCallbackI
      (invoke [_ _window button action mods]
        (update-mouse-button state button action mods))))
  (GLFW/glfwSetCursorPosCallback
    window
    (reify GLFWCursorPosCallbackI
      (invoke [_ _window x y]
        (queue-event state
                     #(assoc % :cursor {:x x :y y})
                     {:type :mouse/moved :x x :y y}))))
  (GLFW/glfwSetScrollCallback
    window
    (reify GLFWScrollCallbackI
      (invoke [_ _window x-offset y-offset]
        (queue-event state identity
                     {:type :mouse/scrolled
                      :x-offset x-offset
                      :y-offset y-offset}))))
  (GLFW/glfwSetJoystickCallback
    (reify GLFWJoystickCallbackI
      (invoke [_ joystick-id event]
        (update-joystick state joystick-id event))))
  nil)

(defn destroy-global-callbacks!
  "Removes and frees GLFW callbacks that are not owned by a window."
  []
  (when-let [callback (GLFW/glfwSetJoystickCallback nil)]
    (.free callback))
  nil)

(defn- gamepad-state
  [joystick-id]
  (with-open [stack (MemoryStack/stackPush)]
    (let [state (GLFWGamepadState/malloc stack)]
      (when (GLFW/glfwGetGamepadState joystick-id state)
        {:id joystick-id
         :name (GLFW/glfwGetGamepadName joystick-id)
         :axes (zipmap gamepad-axis-names
                       (mapv #(.get (.axes state) %)
                             (range (count gamepad-axis-names))))
         :buttons (into #{}
                        (keep-indexed
                          (fn [index button-name]
                            (when (= GLFW/GLFW_PRESS
                                     (.get (.buttons state) index))
                              button-name)))
                        gamepad-button-names)}))))

(defn poll-gamepads
  "Returns standardized gamepad state keyed by joystick ID."
  []
  (into {}
        (keep (fn [joystick-id]
                (when-let [state (gamepad-state joystick-id)]
                  [joystick-id state])))
        (range GLFW/GLFW_JOYSTICK_1 (inc GLFW/GLFW_JOYSTICK_LAST))))

(defn take-frame!
  "Returns held state, queued events, and current gamepads, then clears events."
  [state]
  (let [[frame _] (swap-vals! state #(assoc % :events []))]
    (assoc frame :gamepads (poll-gamepads))))

(defn pressed-key-events
  "Returns key names for press events in an input frame."
  [frame]
  (into []
        (keep #(when (= :key/pressed (:type %)) (:key %)))
        (:events frame)))
