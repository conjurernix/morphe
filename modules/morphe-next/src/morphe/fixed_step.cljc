(ns morphe.fixed-step
  "Pure fixed-timestep scheduling for Morphe games."
  (:require [morphe.core :as game]
            [morphe.internal.event :as event]))

(def default-options
  {:update-hz 60
   :max-catch-up-updates 5
   :max-frame-seconds 0.25})

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :fixed-step))))

(defn- finite-nonnegative-number?
  [value]
  (and (number? value)
       (not (neg? value))
       #?(:clj (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(defn- validate-options!
  [{:keys [update-hz max-catch-up-updates max-frame-seconds] :as options}]
  (when-not (and (pos-int? update-hz)
                 (pos-int? max-catch-up-updates)
                 (finite-nonnegative-number? max-frame-seconds)
                 (pos? max-frame-seconds))
    (fail! "Fixed-step options are invalid" {:options options}))
  options)

(defn- validate-loop!
  [{version :morphe.fixed-step/version
    :keys [game previous-game accumulator-seconds pending-events tick options]
    :as loop-state}]
  (when-not (and (map? loop-state)
                 (= 1 version)
                 (map? game)
                 (map? previous-game)
                 (finite-nonnegative-number? accumulator-seconds)
                 (vector? pending-events)
                 (every? event/event? pending-events)
                 (nat-int? tick)
                 (map? options))
    (fail! "Fixed-step state is malformed" {:value loop-state}))
  (validate-options! options)
  loop-state)

(defn- validate-inputs!
  [sources elapsed-seconds external-events]
  (when-not (and (sequential? sources) (every? fn? sources))
    (fail! "Fixed-step sources must be sequential functions" {:value sources}))
  (when-not (finite-nonnegative-number? elapsed-seconds)
    (fail! "Elapsed time must be finite and nonnegative"
           {:value elapsed-seconds}))
  (when-not (and (sequential? external-events)
                 (every? event/event? external-events))
    (fail! "Fixed-step events must be created with event"
           {:value external-events})))

(defn create
  "Creates immutable fixed-step state for a game and optional scheduler settings."
  ([game-state]
   (create game-state {}))
  ([game-state options]
   (let [options (validate-options! (merge default-options options))]
     ;; A zero-event step validates the game without invoking entity handlers.
     (game/step game-state [] 0.0 [])
     {:morphe.fixed-step/version 1
      :game game-state
      :previous-game game-state
      :accumulator-seconds 0.0
      :pending-events []
      :tick 0
      :options options})))

(defn reset-timing
  "Drops accumulated time and pending gameplay input after a pause or clock reset."
  [loop-state]
  (let [{:keys [game] :as validated} (validate-loop! loop-state)]
    (assoc validated
           :previous-game game
           :accumulator-seconds 0.0
           :pending-events [])))

(defn queue-events
  "Queues mapped events without advancing simulation time."
  [loop-state external-events]
  (let [validated (validate-loop! loop-state)]
    (validate-inputs! [] 0.0 external-events)
    (update validated :pending-events into external-events)))

(defn previous-entity-state
  "Returns an entity's previous fixed-update state from an adapter render context."
  [{entity-id :morphe.render/entity-id
    previous-game :morphe.render/previous-game}]
  (some-> previous-game
          (get-in [:entities entity-id])
          game/entity-state))

(defn interpolate-number
  "Interpolates from previous to current by an alpha between zero and one."
  [previous current alpha]
  (when-not (and (number? current)
                 (or (nil? previous) (number? previous))
                 (finite-nonnegative-number? alpha)
                 (<= alpha 1.0))
    (fail! "Interpolation requires numbers and alpha in [0.0, 1.0]"
           {:previous previous :current current :alpha alpha}))
  (if (nil? previous)
    current
    (+ previous (* (- current previous) alpha))))

(defn- due-update-count
  [accumulator step-seconds]
  #?(:clj (long (Math/floor (+ (/ accumulator step-seconds) 1.0e-12)))
     :cljs (long (js/Math.floor (+ (/ accumulator step-seconds) 1.0e-12)))))

(defn- run-updates
  [{:keys [game tick] :as loop-state} sources update-count step-seconds events]
  (loop [remaining update-count
         current-game game
         previous-game (:previous-game loop-state)
         next-tick tick
         next-events events
         effects []
         ticks []]
    (if (zero? remaining)
      {:game current-game
       :previous-game previous-game
       :tick next-tick
       :effects effects
       :ticks ticks}
      (let [result (game/step current-game sources step-seconds next-events)
            updated-game (:game result)
            updated-tick (inc next-tick)]
        (recur (dec remaining)
               updated-game
               current-game
               updated-tick
               []
               (into effects (:effects result))
               (conj ticks {:tick updated-tick :events next-events}))))))

(defn advance
  "Advances fixed-step state by elapsed seconds and queues mapped external events."
  [loop-state sources elapsed-seconds external-events]
  (let [{:keys [accumulator-seconds pending-events options] :as loop-state}
        (validate-loop! loop-state)
        _ (validate-inputs! sources elapsed-seconds external-events)
        {:keys [update-hz max-catch-up-updates max-frame-seconds]} options
        step-seconds (/ 1.0 update-hz)
        accepted-seconds (min elapsed-seconds max-frame-seconds)
        accumulated-seconds (+ accumulator-seconds accepted-seconds)
        due-updates (due-update-count accumulated-seconds step-seconds)
        update-count (min due-updates max-catch-up-updates)
        dropped-updates (- due-updates update-count)
        next-accumulator (max 0.0
                              (- accumulated-seconds
                                 (* due-updates step-seconds)))
        queued-events (into pending-events external-events)
        update-result (run-updates loop-state sources update-count
                                   step-seconds queued-events)
        next-loop (-> loop-state
                      (assoc :game (:game update-result)
                             :previous-game (:previous-game update-result)
                             :accumulator-seconds next-accumulator
                             :pending-events (if (pos? update-count)
                                               []
                                               queued-events)
                             :tick (:tick update-result)))]
    {:loop next-loop
     :effects (:effects update-result)
     :updates update-count
     :alpha (/ next-accumulator step-seconds)
     :dropped-seconds (+ (- elapsed-seconds accepted-seconds)
                         (* dropped-updates step-seconds))
     :ticks (:ticks update-result)}))
