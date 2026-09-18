(ns snake-event.game-test
  (:require [clojure.test :refer [deftest is]]
            [morphe.core :as game]
            [snake-event.board :as board]
            [snake-event.components.position :as position]
            [snake-event.food :as food]
            [snake-event.game :as snake-game]
            [snake-event.loop :as loop]
            [snake-event.snake :as snake]))

(defn- create-game
  []
  (snake-game/initial-game first))

(deftest starts-with-local-state-and-component-state
  (let [state (create-game)
        {:keys [phase]} (snake-game/snake-state state)]
    (is (= :playing phase))
    (is (= [0 0] (food/position state)))
    (is (= 0 (snake-game/score state)))))

(deftest render-data-contains-food-and-snake-segments
  (let [render-data (game/render-data (create-game) {:camera :main})]
    (is (= 4 (count render-data)))
    (is (every? (fn [{:keys [kind]}] (= :rectangle kind)) render-data))))

(deftest tick-moves-the-snake
  (let [{next-game :game} (snake-game/step
                            (create-game)
                            0.1
                            [(game/event snake/id [::snake/tick])])
        {:keys [cells]} (snake-game/snake-state next-game)]
    (is (= [[11 8] [10 8] [9 8]] cells))))

(deftest input-affects-the-same-frame-tick
  (let [initial (create-game)
        tick-source (fn [_context _dt]
                      [(game/event snake/id [::snake/tick])])
        {next-game :game} (game/step initial [tick-source] 0.1
                                     [(game/event snake/id [::snake/turn :up])])
        {:keys [cells]} (snake-game/snake-state next-game)]
    (is (= [[10 7] [10 8] [9 8]] cells))))

(deftest targeted-turn-prevents-reversal
  (let [{next-game :game} (snake-game/step
                            (create-game)
                            0.0
                            [(game/event snake/id [::snake/turn :left])])
        {:keys [pending-direction]} (snake-game/snake-state next-game)]
    (is (= :right pending-direction))))

(deftest eating-emits-a-message-and-component-owns-score
  (let [initial (create-game)
        initial (update-in initial [:entities snake/id :state]
                           assoc :food-position [11 8])
        {next-game :game :keys [effects]}
        (snake-game/step initial 0.1
                         [(game/event snake/id [::snake/tick])])
        {:keys [cells]} (snake-game/snake-state next-game)]
    (is (= 1 (snake-game/score next-game)))
    (is (= 4 (count cells)))
    (is (= (board/choose-free-cell cells first)
           (food/position next-game)))
    (is (not (some #{(food/position next-game)} cells)))
    (is (= [[:sound/play :snake/ate]] effects))))

(deftest collision-and-restart-are-events
  (let [initial (create-game)
        initial (update-in initial [:entities snake/id :state]
                           assoc :cells [[23 8] [22 8] [21 8]])
        {lost-game :game} (snake-game/step initial 0.1
                                           [(game/event snake/id [::snake/tick])])
        {restarted-game :game} (snake-game/step
                                lost-game
                                0.0
                                [(game/event snake/id [::snake/restart])])
        {lost-phase :phase} (snake-game/snake-state lost-game)
        {restarted-phase :phase} (snake-game/snake-state restarted-game)]
    (is (= :lost lost-phase))
    (is (= :playing restarted-phase))))

(deftest position-component-owns-set-and-restart-events
  (let [initial (create-game)
        {moved-game :game} (snake-game/step
                            initial
                            0.0
                            [(game/event food/id [::position/set [2 3] nil nil])])
        {restarted-game :game} (snake-game/step
                                moved-game
                                0.0
                                [(game/event food/id [::position/restart])])]
    (is (= [2 3] (food/position moved-game)))
    (is (= [0 0] (food/position restarted-game)))))

(deftest restart-input-targets-snake-and-food
  (is (= [(game/event snake/id [::snake/restart])
          (game/event food/id [::food/restart snake/id
                               board/initial-snake-cells])]
         (loop/input-events nil #{:r}))))

(deftest restart-chooses-an-available-tile-and-syncs-snake
  (let [calls (atom 0)
        choose-cell (fn [available]
                      (if (= 1 (swap! calls inc))
                        (first available)
                        (last available)))
        initial (snake-game/initial-game choose-cell)
        {restarted :game} (snake-game/step initial 0.0
                                           (loop/input-events initial #{:r}))
        {:keys [cells food-position]} (snake-game/snake-state restarted)]
    (is (= 2 @calls))
    (is (= [23 17] food-position (food/position restarted)))
    (is (not (some #{food-position} cells)))))

(deftest filling-the-board-wins-without-choosing-a-tile
  (let [calls (atom 0)
        choose-cell (fn [available]
                      (swap! calls inc)
                      (first available))
        {:keys [columns rows]} board/grid
        occupied (vec (for [y (range rows)
                            x (range columns)]
                        [x y]))
        {won-game :game} (snake-game/step
                           (snake-game/initial-game choose-cell)
                           0.0
                           [(game/event food/id [::food/eaten snake/id occupied])])
        {:keys [phase]} (snake-game/snake-state won-game)]
    (is (= :won phase))
    (is (= 1 @calls))))
