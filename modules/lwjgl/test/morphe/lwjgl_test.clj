(ns morphe.lwjgl-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.adapters.lwjgl :as lwjgl]
            [morphe.components.2d :as c2d]
            [morphe.components.render-2d :as render-2d]
            [morphe.components.render-3d :as render-3d]
            [morphe.core :as ecs]))

(deftest drawable-entities-are-layer-ordered
  (let [[world first-entity]
        (ecs/spawn (ecs/world)
                   {c2d/position-key (c2d/position 0.0 0.0)
                    render-2d/shape-key (assoc (render-2d/circle 5.0 [255 0 0]) :layer 20)})
        [world second-entity]
        (ecs/spawn world
                   {c2d/position-key (c2d/position 10.0 0.0)
                    render-3d/cube-key (render-3d/cube 1.0 [0.0 1.0 0.0] 10)})]
    (is (= [second-entity first-entity]
           (mapv :entity (lwjgl/drawable-entities world))))))

(deftest cube-vertices-have-the-expected-size
  (is (= 108 (count (lwjgl/cube-vertices 1.0))))
  (is (= 3 (count (lwjgl/color [255 128 0]))))
  (is (every? true?
            (map #(<= (Math/abs (- %1 %2)) 1.0e-6)
                 [1.0 0.5019608 0.0]
                 (lwjgl/color [255 128 0])))))

(deftest camera-defaults-are-explicit
  (is (= {:position [0.0 0.0 0.0]
          :rotation [0.0 0.0 0.0 1.0]
          :fov 60.0
          :near 0.1
          :far 100.0}
         (render-3d/camera {}))))

(deftest input-state-is-immutable
  (let [initial #{}
        pressed (lwjgl/press-key initial :a)
        released (lwjgl/release-key pressed :a)]
    (is (= #{} initial))
    (is (= #{:a} pressed))
    (is (= #{} released))))

(deftest invalid-options-are-rejected
  (testing "callbacks"
    (is (thrown? clojure.lang.ExceptionInfo
                 (lwjgl/validate-options {:command-fn :invalid})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lwjgl/validate-options {:event-fn :invalid})))))
