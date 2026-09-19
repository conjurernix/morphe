(ns morphe.random-cljs-test
  (:require [morphe.application :as application]
            [morphe.collision :as collision]
            [morphe.core :as game]
            [morphe.fixed-step :as fixed-step]
            [morphe.random :as random]
            [morphe.replay :as replay]))

(defn- check!
  [expected actual]
  (when-not (= expected actual)
    (throw (js/Error. (str "Expected " (pr-str expected)
                           ", received " (pr-str actual))))))

(defn- create-counter
  [state]
  (game/typed-entity
    :counter state []
    (fn [current _context [_ amount]]
      [(update current :total + amount) []])))

(defn -main
  [& _]
  (let [[next-random first-value]
        (random/next-uint (random/from-words [1 2 3 4]))
        initial-game (game/add-entity
                       (game/game)
                       :counter
                       (create-counter {:total 0}))
        fixed-result (fixed-step/advance
                       (fixed-step/create initial-game {:update-hz 10})
                       []
                       0.1
                       [(game/event :counter [:counter/add 3])])
        final-game (-> fixed-result :loop :game)
        recording (-> (replay/start-recording (game/snapshot initial-game) 10)
                      (replay/record-ticks (:ticks fixed-result))
                      (replay/finish-recording (game/snapshot final-game)))
        replayed (replay/play recording {:counter create-counter} [])
        collision-index (collision/create-index
                         16
                         [{:id :wall :x 20 :y 8 :width 8 :height 16}])]
    (check! 11520 first-value)
    (check! [7 0 1026 12288] (:words next-random))
    (check! (random/create -1) (random/create 4294967295))
    (check! false (:paused? (application/create)))
    (check! 3 (-> replayed :game (game/get-entity :counter)
                  game/entity-state :total))
    (check! [:wall] (mapv :id (collision/query collision-index
                                               {:x 20 :y 8 :width 8 :height 16})))
    (check! :wall (get-in (collision/first-hit collision-index
                                               {:x 0 :y 8} {:x 1 :y 0} 32)
                          [:collider :id]))
    (println "ClojureScript runtime smoke tests passed.")))
