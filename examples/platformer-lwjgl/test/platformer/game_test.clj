(ns platformer.game-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [morphe.core :as core]
            [morphe.fixed-step :as fixed-step]
            [morphe.replay :as replay]
            [platformer.entities.coin :as coin]
            [platformer.entities.enemy :as enemy]
            [platformer.entities.player :as player]
            [platformer.entities.projectile :as projectile]
            [platformer.game :as game]
            [platformer.main :as main]
            [platformer.world :as world]))

(defn- run-recorded-second
  [frames-per-second]
  (let [initial (fixed-step/create (game/initial-game) {:update-hz 60})
        input (core/event
                player/id
                [::player/input {:left false :right true
                                 :jump false :shoot true}])]
    (reduce (fn [{:keys [loop ticks]} frame]
              (let [result (fixed-step/advance
                             loop
                             [game/tick-source]
                             (/ 1.0 frames-per-second)
                             (if (zero? frame) [input] []))]
                {:loop (:loop result)
                 :ticks (into ticks (:ticks result))}))
            {:loop initial :ticks []}
            (range frames-per-second))))

(deftest wasd-input-maps-to-player-actions
  (is (= {:left true :right false :jump false :shoot false}
         (-> (main/input-events nil #{:a} []) :events first :message second)))
  (is (= {:left false :right true :jump false :shoot false}
         (-> (main/input-events nil #{:d} []) :events first :message second)))
  (is (= {:left false :right false :jump true :shoot false}
         (-> (main/input-events nil #{} [:w]) :events first :message second))))

(deftest application-input-can-quit-and-pause
  (is (= [[:app/quit]]
         (:controls (main/input-events nil #{} [:escape]))))
  (is (= [[:app/pause]]
         (:controls
           (main/handle-application-event
             {} nil {:type :morphe.app/focus-changed :focused? false}))))
  (is (= [[:app/resume]]
         (:controls
           (main/handle-application-event
             {} nil {:type :morphe.app/focus-changed :focused? true})))))

(deftest fixed-updates-and-replay-are-frame-rate-independent
  (let [runs (mapv run-recorded-second [30 60 144])
        snapshots (mapv #(core/snapshot (-> % :loop :game)) runs)
        initial-snapshot (core/snapshot (game/initial-game))
        recording (-> (replay/start-recording initial-snapshot 60)
                      (replay/record-ticks (:ticks (second runs)))
                      (replay/finish-recording (second snapshots)))
        replayed (replay/play (edn/read-string (pr-str recording))
                              game/factories
                              [game/tick-source])]
    (is (= [60 60 60] (mapv #(-> % :loop :tick) runs)))
    (is (apply = snapshots))
    (is (= (second snapshots) (:snapshot replayed)))))

(deftest spacebar-launches-a-projectile
  (let [input (core/event player/id
                          [::player/input {:left false :right false
                                           :jump false :shoot true}])
        result (core/step (game/initial-game) [game/tick-source] 0.016 [input])
        projectile-state (core/entity-state
                          (core/get-entity (:game result) [:projectile 0]))]
    (is (= 1 (:projectile-sequence
              (core/entity-state
                (core/get-entity (:game result) player/id)))))
    (is (= [:projectile 0] (:id projectile-state)))
    (is (some #{[:sound/play :shoot]} (:effects result)))))

(deftest projectile-hit-defeats-enemy
  (let [[projectile-id projectile-entity] (projectile/create 0 200 440 1)
        initial (core/add-entity (game/initial-game)
                                 projectile-id projectile-entity)
        hit (core/event [::enemy/projectile-hit projectile-id 280 440])
        result (core/step initial [] 0.016 [hit])
        enemy-state (core/entity-state
                     (core/get-entity (:game result) (first enemy/ids)))
        projectile-state (core/get-entity (:game result) projectile-id)]
    (is (:defeated? enemy-state))
    (is (nil? projectile-state))
    (is (some #{[:sound/play :enemy-hit]} (:effects result)))))

(deftest enemies-patrol-and-reverse-at-their-boundaries
  (let [enemy-id (first enemy/ids)
        tick (core/event enemy-id [:platformer.game/tick 2.0])
        result (core/step (game/initial-game) [] 0.016 [tick])
        enemy-state (core/entity-state (core/get-entity (:game result) enemy-id))]
    (is (= 340 (:x enemy-state)))
    (is (= -1 (:direction enemy-state)))))

(deftest enemy-impact-respawns-player
  (let [impact (core/event [::enemy/player-at player/id 280 440])
        result (core/step (game/initial-game) [] 0.016 [impact])
        player-state (core/entity-state (core/get-entity (:game result) player/id))]
    (is (= world/spawn-position (select-keys player-state [:x :y])))
    (is (seq (:particles player-state)))
    (is (some #{[:sound/play :hit]} (:effects result)))))

(deftest initial-game-renders-platformer-scene
  (let [render-data (core/render-data (game/initial-game))]
    (is (some #(= :sprite (:kind %)) render-data))
    (is (some #(= :rectangle (:kind %)) render-data))
    (is (some #(and (= [48 59 92] (:fill %))
                    (= (:y %) 500))
              render-data))
    (is (some #(and (= [224 78 92] (:fill %))
                    (= (:x %) 390))
              render-data))))

(deftest player-responds-to-jump-event
  (let [initial (game/initial-game)
        input (core/event player/id
                          [::player/input {:left false :right false :jump true}])
        stepped (core/step initial [game/tick-source] 0.016 [input])
        player-state (core/entity-state (core/get-entity (:game stepped) player/id))]
    (is (neg? (:vy player-state)))
    (is (= :sound/play (ffirst (:effects stepped))))))

(deftest player-stays-on-the-floor-without-input
  (let [result (core/step (game/initial-game) [game/tick-source] 0.016 [])
        player-state (core/entity-state (core/get-entity (:game result) player/id))]
    (is (= 440 (:y player-state)))
    (is (:grounded player-state))
    (is (empty? (:effects result)))))

(deftest collected-coin-renders-no-data
  (let [coin-entity (core/get-entity (game/initial-game) coin/id)
        state (core/entity-state coin-entity)
        rendered (coin/render (assoc state :collected? true) {} {})]
    (is (= [] rendered))))

(deftest hazard-resets-player
  (let [[first-projectile-id first-projectile] (projectile/create 0 280 440 1)
        [second-projectile-id second-projectile] (projectile/create 1 600 440 1)
        armed-game (-> (game/initial-game)
                       (core/add-entity first-projectile-id first-projectile)
                       (core/add-entity second-projectile-id second-projectile))
        enemies-defeated
        (:game (core/step armed-game [] 0.016
                          [(core/event [::enemy/projectile-hit
                                        first-projectile-id 280 440])
                           (core/event [::enemy/projectile-hit
                                        second-projectile-id 600 440])]))
        input (core/event player/id
                          [::player/input {:left false :right true :jump false}])
        [final-game hit? burst?]
        (loop [current enemies-defeated steps 0 hit? false burst? false]
          (let [result (core/step current [game/tick-source] 0.016 [input])
                hit-this-frame? (some #{[:sound/play :hit]} (:effects result))
                player-state (core/entity-state
                              (core/get-entity (:game result) player/id))
                burst-this-frame? (some #(= [255 91 43] (:color %))
                                        (:particles player-state))]
            (if (= steps 120)
              [(:game result) (or hit? hit-this-frame?) (or burst? burst-this-frame?)]
              (recur (:game result) (inc steps)
                     (or hit? hit-this-frame?)
                     (or burst? burst-this-frame?)))))
        player-state (core/entity-state (core/get-entity final-game player/id))]
    (is hit?)
    (is (<= (:x player-state) (- world/width (/ (first world/player-size) 2))))
    (is burst?)))

(deftest player-can-land-on-upper-platform
  (let [input (core/event player/id
                          [::player/input {:left false :right true :jump false}])
        jump (core/event player/id
                         [::player/input {:left false :right true :jump true}])
        final-game (loop [current (game/initial-game) steps 0]
                     (if (= steps 50)
                       current
                       (recur (:game (core/step current [game/tick-source] 0.016
                                                [(if (zero? steps) jump input)]))
                              (inc steps))))
        player-state (core/entity-state (core/get-entity final-game player/id))]
    (is (:grounded player-state))
    (is (= 358 (:y player-state)))))
