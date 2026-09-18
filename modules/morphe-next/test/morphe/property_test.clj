(ns morphe.property-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [morphe.core :as game]))

(def increment-events
  (gen/vector (gen/choose -100 100) 0 100))

(defn- counter-game
  []
  (game/add-entity
    (game/game)
    :counter
    (game/entity {:total 0} []
                 (fn [state _context [_ amount]]
                   [(update state :total + amount) []]))))

(deftest steps-are-immutable-and-repeatable
  (let [{:keys [pass?] :as result}
        (tc/quick-check
          100
          (prop/for-all [amounts increment-events]
            (let [initial (counter-game)
                  events (mapv #(game/event :counter [:increment %]) amounts)
                  first-result (game/step initial [] 0.0 events)
                  second-result (game/step initial [] 0.0 events)]
              (and (= 0 (-> initial (game/get-entity :counter)
                            game/entity-state :total))
                   (= first-result second-result)
                   (= (reduce + 0 amounts)
                      (-> first-result :game (game/get-entity :counter)
                          game/entity-state :total))))))]
    (is pass? result)))

(deftest broadcast-results-follow-insertion-order
  (let [ids (gen/vector-distinct gen/keyword {:min-elements 1 :max-elements 30})
        {:keys [pass?] :as result}
        (tc/quick-check
          100
          (prop/for-all [entity-ids ids]
            (let [trace-entity (fn [entity-id]
                                 (game/entity {} []
                                              (fn [state _context _message]
                                                [state [(game/effect
                                                          [:trace/entity entity-id])]])))
                  initial (reduce (fn [state entity-id]
                                    (game/add-entity state entity-id
                                                     (trace-entity entity-id)))
                                  (game/game)
                                  entity-ids)
                  {:keys [effects]} (game/step initial [] 0.0
                                                [(game/event [:trace])])]
              (= entity-ids (mapv second effects)))))]
    (is pass? result)))
