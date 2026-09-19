(ns morphe.application
  "Pure application events and controls shared by Morphe adapters."
  (:require [morphe.internal.event :as event]))

(def event-types
  #{:morphe.app/quit-requested
    :morphe.app/focus-changed
    :morphe.app/resized
    :morphe.app/content-scale-changed})

(def control-types
  #{:app/quit :app/cancel-quit :app/pause :app/resume})

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :application))))

(defn- finite-positive?
  [value]
  (and (number? value)
       (pos? value)
       #?(:clj (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(defn quit-requested
  "Creates a window close-request application event."
  []
  {:type :morphe.app/quit-requested})

(defn focus-changed
  "Creates an application event containing the window focus state."
  [focused?]
  (when-not (boolean? focused?)
    (fail! "Application focus must be boolean" {:value focused?}))
  {:type :morphe.app/focus-changed :focused? focused?})

(defn resized
  "Creates an application event containing a positive framebuffer size."
  [width height]
  (when-not (and (pos-int? width) (pos-int? height))
    (fail! "Application size must contain positive integers"
           {:width width :height height}))
  {:type :morphe.app/resized :width width :height height})

(defn content-scale-changed
  "Creates an application event containing positive content scale values."
  [x y]
  (when-not (and (finite-positive? x) (finite-positive? y))
    (fail! "Application content scale must contain positive finite numbers"
           {:x x :y y}))
  {:type :morphe.app/content-scale-changed
   :content-scale {:x (double x) :y (double y)}})

(defn create
  "Creates immutable application state for an adapter."
  ([]
   (create {}))
  ([{:keys [state width height content-scale]
     :or {state {} width 1 height 1 content-scale {:x 1.0 :y 1.0}}}]
   (when-not (map? state)
     (fail! "Application user state must be a map" {:value state}))
   (resized width height)
   (let [scale-event (content-scale-changed (:x content-scale) (:y content-scale))]
     {:morphe.application/version 1
      :running? true
      :paused? false
      :focused? true
      :width width
      :height height
      :content-scale (:content-scale scale-event)
      :state state})))

(defn- valid-application?
  [{version :morphe.application/version
    :keys [running? paused? focused? width height content-scale state]
    :as application}]
  (and (map? application)
       (= 1 version)
       (boolean? running?)
       (boolean? paused?)
       (boolean? focused?)
       (pos-int? width)
       (pos-int? height)
       (map? content-scale)
       (finite-positive? (:x content-scale))
       (finite-positive? (:y content-scale))
       (map? state)))

(defn- validate-event!
  [{:keys [type focused? width height content-scale] :as application-event}]
  (when-not (and (map? application-event) (contains? event-types type))
    (fail! "Application event is invalid" {:value application-event}))
  (case type
    :morphe.app/focus-changed
    (when-not (boolean? focused?)
      (fail! "Application focus must be boolean" {:value application-event}))

    :morphe.app/resized
    (when-not (and (pos-int? width) (pos-int? height))
      (fail! "Application size must contain positive integers"
             {:value application-event}))

    :morphe.app/content-scale-changed
    (when-not (and (map? content-scale)
                   (finite-positive? (:x content-scale))
                   (finite-positive? (:y content-scale)))
      (fail! "Application content scale must contain positive finite numbers"
             {:value application-event}))

    nil)
  application-event)

(defn- validate-result!
  [{:keys [app events controls] :as result}]
  (when-not (and (map? result)
                 (map? app)
                 (sequential? events)
                 (every? event/event? events)
                 (sequential? controls)
                 (every? #(and (vector? %)
                               (= 1 (count %))
                               (contains? control-types (first %)))
                         controls))
    (fail! "Application event handler returned an invalid result"
           {:value result}))
  {:app app :events (vec events) :controls (vec controls)})

(defn- update-built-in-state
  [application {:keys [type focused? width height content-scale]}]
  (case type
    :morphe.app/quit-requested (assoc application :running? false)
    :morphe.app/focus-changed (assoc application :focused? focused?)
    :morphe.app/resized (assoc application :width width :height height)
    :morphe.app/content-scale-changed
    (assoc application :content-scale
           (:content-scale
            (content-scale-changed (:x content-scale) (:y content-scale))))))

(defn- apply-control
  [application [control-type]]
  (case control-type
    :app/quit (assoc application :running? false)
    :app/cancel-quit (assoc application :running? true)
    :app/pause (assoc application :paused? true)
    :app/resume (assoc application :paused? false)))

(defn- valid-controls?
  [controls]
  (and (sequential? controls)
       (every? #(and (vector? %)
                     (= 1 (count %))
                     (contains? control-types (first %)))
               controls)))

(defn apply-controls
  "Applies ordered application controls to application state."
  [application controls]
  (when-not (valid-application? application)
    (fail! "Application state is malformed" {:value application}))
  (when-not (valid-controls? controls)
    (fail! "Application controls are invalid" {:value controls}))
  (reduce apply-control application controls))

(defn handle-event
  "Reduces one application event and returns application state plus game events."
  [application game-state application-event app-event-fn]
  (when-not (valid-application? application)
    (fail! "Application state is malformed" {:value application}))
  (validate-event! application-event)
  (when-not (or (nil? app-event-fn) (fn? app-event-fn))
    (fail! "Application event handler must be a function"
           {:value app-event-fn}))
  (let [built-in-state (update-built-in-state application application-event)
        result (if app-event-fn
                 (validate-result!
                   (app-event-fn (:state built-in-state)
                                 game-state
                                 application-event))
                 {:app (:state built-in-state) :events [] :controls []})
        next-application (-> (apply-controls built-in-state (:controls result))
                             (assoc :state (:app result)))]
    {:application next-application
     :events (:events result)}))

(defn handle-events
  "Reduces ordered application events and concatenates their game events."
  [application game-state application-events app-event-fn]
  (when-not (sequential? application-events)
    (fail! "Application events must be sequential"
           {:value application-events}))
  (reduce (fn [{:keys [application events]} application-event]
            (let [result (handle-event application game-state
                                       application-event app-event-fn)]
              {:application (:application result)
               :events (into events (:events result))}))
          {:application application :events []}
          application-events))

(defn error-action
  "Returns :rethrow by default or validates an application's fatal-error decision."
  [error-fn throwable phase-data]
  (when-not (or (nil? error-fn) (fn? error-fn))
    (fail! "Application error handler must be a function" {:value error-fn}))
  (let [action (if error-fn
                 (error-fn throwable phase-data)
                 :rethrow)]
    (when-not (contains? #{:rethrow :close} action)
      (fail! "Application error handler must return :rethrow or :close"
             {:value action}))
    action))
