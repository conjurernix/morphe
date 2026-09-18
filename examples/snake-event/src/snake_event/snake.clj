(ns snake-event.snake
  (:require [morphe.core :as game]
            [snake-event.board :as board]
            [snake-event.food :as-alias food]))

(def id :snake)
(def score-key ::score)
(def directions board/directions)
(def opposites board/opposites)

(defn valid-turn? [current requested]
  (and (contains? directions requested)
       (not= requested (opposites current))))

(def score-component
  (game/component score-key
    (fn [state _context [message-type]]
      (case message-type
        ::food-eaten
        [(update state score-key (fnil inc 0))
         [(game/effect [:sound/play :snake/ate])]]

        ::restart
        [(assoc state score-key 0) []]

        [state []]))))

(defn initial-state [food-id food-position]
  {:cells board/initial-snake-cells
   :direction :right
   :pending-direction :right
   :food-id food-id
   :food-position food-position
   :phase :playing
   score-key 0})

(defn- tick-state
  [{:keys [cells pending-direction food-position food-id] :as state}]
  (let [[head] cells
        next-head (board/add-cell head pending-direction)
        eating? (= next-head food-position)
        collision-cells (if eating? cells (butlast cells))
        collision? (or (not (board/inside-grid? next-head))
                       (some #{next-head} collision-cells))]
    (cond
      collision?
      [(assoc state :phase :lost) [(game/send id [::lost])]]

      :else
      (let [next-cells (if eating?
                         (into [next-head] cells)
                         (into [next-head] (butlast cells)))]
        [(assoc state :cells next-cells :direction pending-direction)
         (cond-> []
           eating? (conj (game/send food-id
                                    [::food/eaten id next-cells])))]))))

(defmulti update-snake
  (fn [_state _context [message-type]] message-type))

(defmethod update-snake ::turn
  [{:keys [direction] :as state} _context [_ requested]]
  [(if (valid-turn? direction requested)
     (assoc state :pending-direction requested)
     state)
   []])

(defmethod update-snake ::tick
  [{:keys [phase] :as state} _context _message]
  (if (= :playing phase) (tick-state state) [state []]))

(defmethod update-snake ::food-moved
  [state _context [_ position]]
  [(assoc state :food-position position) []])

(defmethod update-snake ::won
  [state _context _message]
  [(assoc state :phase :won) []])

(defmethod update-snake ::restart
  [{:keys [food-id food-position] ::keys [score]} _context _message]
  [(assoc (initial-state food-id food-position) score-key score) []])

(defmethod update-snake :default
  [state _context _message]
  [state []])

(defn- cell->renderable
  [[x y] fill {render-cell-size :cell-size}]
  (let [size (or render-cell-size board/cell-size)]
    {:kind :rectangle :layer 0
     :x (* (+ x 0.5) size) :y (* (+ y 0.5) size)
     :width size :height size :fill fill}))

(defn render-snake
  [{:keys [cells]} _context render-context]
  (map #(cell->renderable % [44 117 255] render-context) cells))

(defn create [food-id food-position]
  (game/entity (initial-state food-id food-position)
               [score-component]
               update-snake
               render-snake))

(defn state [game-state]
  (game/entity-state (game/get-entity game-state id)))

(defn score [game-state]
  (let [{::keys [score]} (state game-state)]
    score))
