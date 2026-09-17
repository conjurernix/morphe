(ns morphe.core-test
  (:require [clojure.test :refer [deftest is]]
            [morphe.components.2d :as c2d]
            [morphe.core :as ecs]
            [morphe.query :as q]
            [morphe.runtime :as runtime]
            [morphe.system :as system]))

(deftest entity-and-component-lifecycle
  (let [[world entity] (ecs/spawn (ecs/world) {c2d/position-key (c2d/position 1.0 2.0)})
        world' (ecs/add-component world entity c2d/velocity-key (c2d/velocity 3.0 4.0))]
    (is (ecs/alive? world entity))
    (is (= {c2d/x 1.0 c2d/y 2.0} (ecs/component world entity c2d/position-key)))
    (is (ecs/has-component? world' entity c2d/velocity-key))
    (is (= 7.0
           (get-in (ecs/update-component world' entity c2d/velocity-key update c2d/x + 4.0)
                   [:components c2d/velocity-key entity c2d/x])))
    (is (not (ecs/alive? (ecs/destroy world' entity) entity)))))

(deftest query-predicates
  (let [[world first-entity] (ecs/spawn (ecs/world) {c2d/position-key (c2d/position 0.0 0.0)})
        [world second-entity] (ecs/spawn world {c2d/position-key (c2d/position 1.0 1.0)
                                                c2d/velocity-key (c2d/velocity 1.0 0.0)})]
    (is (= [first-entity second-entity] (q/query world (q/all c2d/position-key))))
    (is (= [second-entity] (q/query world (q/all c2d/position-key c2d/velocity-key))))
    (is (= [first-entity] (q/query world (q/all c2d/position-key (q/without c2d/velocity-key)))))
    (is (= [first-entity second-entity]
           (q/query world (q/all c2d/position-key (q/optional c2d/velocity-key)))))))

(deftest runtime-returns-persistent-world-and-events
  (let [[world entity] (ecs/spawn (ecs/world) {c2d/position-key (c2d/position 0.0 0.0)
                                               c2d/velocity-key (c2d/velocity 2.0 0.0)})
        movement (system/entity-system
                   {:name :test/movement :query (q/all c2d/position-key c2d/velocity-key)
                    :reads #{c2d/position-key c2d/velocity-key} :writes #{c2d/position-key}}
                   (fn [current-world current-entity dt]
                     (let [position (ecs/component current-world current-entity c2d/position-key)
                           velocity (ecs/component current-world current-entity c2d/velocity-key)]
                       {:world (ecs/set-component
                                 current-world current-entity c2d/position-key
                                 {c2d/x (+ (get position c2d/x) (* (get velocity c2d/x) dt))
                                  c2d/y (get position c2d/y)})
                        :events [{:type :test/moved :entity current-entity}]})))
        result (ecs/step (runtime/runtime [movement]) world 0.5)]
    (is (= 1.0 (get-in result [:world :components c2d/position-key entity c2d/x])))
    (is (= [{:type :test/moved :entity entity}] (:events result)))
    (is (= 0.0 (get-in world [:components c2d/position-key entity c2d/x])))))

(deftest world-systems-use-the-execution-protocol
  (let [world (ecs/world)
        current-system (system/system
                         {:name :test/world
                          :reads #{:test/input}
                          :operation (fn [current-world _dt]
                                       {:world (ecs/set-resource current-world :test/ran true)
                                        :events []})})
        result (system/execute-system current-system world 0.016)]
    (is (satisfies? system/ExecutableSystem current-system))
    (is (= {:name :test/world
            :reads #{:test/input}
            :writes #{}}
           (select-keys (system/system-metadata current-system) [:name :reads :writes])))
    (is (= true (ecs/get-resource (:world result) :test/ran)))))
