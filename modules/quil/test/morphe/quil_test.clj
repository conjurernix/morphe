(ns morphe.quil-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.adapters.quil :as quil]
            [morphe.components.2d :as c2d]
            [morphe.core :as ecs]))

(deftest drawable-entities-are-layer-ordered
  (let [[world first-entity] (ecs/spawn
                               (ecs/world)
                               {c2d/position-key (c2d/position 0.0 0.0)
                                quil/shape-key (assoc (quil/circle 5.0 [255 0 0]) :layer 20)})
        [world second-entity] (ecs/spawn
                                world
                                {c2d/position-key (c2d/position 10.0 0.0)
                                 quil/sprite-key (assoc (quil/sprite :player) :layer 10)})
        [world third-entity] (ecs/spawn
                              world
                              {c2d/position-key (c2d/position 20.0 0.0)})]
    (is (= [second-entity first-entity]
           (mapv :entity (quil/drawable-entities world))))
    (is (= [second-entity first-entity]
           (mapv :entity (quil/drawable-entities
                           (ecs/destroy world third-entity)))))))

(deftest keyboard-state-is-immutable-data
  (let [initial {:pressed-keys #{}}
        pressed (quil/press-key initial :w)
        released (quil/release-key pressed :w)]
    (is (= #{} (:pressed-keys initial)))
    (is (= #{:w} (:pressed-keys pressed)))
    (is (= #{} (:pressed-keys released)))))

(deftest asset-resolution-is-explicit
  (let [image (Object.)]
    (is (identical? image (quil/resolve-asset {:player image} :player)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (quil/resolve-asset {} :missing)))))

(deftest component-constructors-produce-render-data
  (testing "shapes"
    (is (= {:kind :circle :radius 5.0 :fill [255 0 0]}
           (quil/circle 5.0 [255 0 0])))
    (is (= {:kind :rectangle :width 10.0 :height 20.0 :fill [0 255 0]}
           (quil/rectangle 10.0 20.0 [0 255 0]))))
  (testing "sprites"
    (is (= {:asset :player}
           (quil/sprite :player)))))
