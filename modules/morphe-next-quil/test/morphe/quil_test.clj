(ns morphe.quil-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.adapters.quil :as quil]))

(deftest keyboard-state-is-immutable
  (let [initial {:pressed-keys #{}}
        pressed (quil/press-key initial :w)
        released (quil/release-key pressed :w)
        {initial-keys :pressed-keys} initial
        {pressed-keys :pressed-keys} pressed
        {released-keys :pressed-keys} released]
    (is (= #{} initial-keys))
    (is (= #{:w} pressed-keys))
    (is (= #{} released-keys))
    (is (= [:w] (:key-events pressed)))
    (is (= [:w] (:key-events released)))))

(deftest render-descriptors-are-data
  (testing "shapes"
    (is (= {:kind :rectangle :x 10 :y 20 :width 30 :height 40 :fill [1 2 3]}
           (quil/rectangle 10 20 30 40 [1 2 3])))
    (is (= {:kind :circle :x 10 :y 20 :radius 5 :fill [1 2 3]}
           (quil/circle 10 20 5 [1 2 3]))))
  (testing "sprites"
    (is (= {:kind :sprite :asset :player :x 10 :y 20}
           (quil/sprite :player 10 20)))
    (is (= {:kind :sprite :asset :player :x 10 :y 20
            :width 32 :height 48 :tint [255 0 0]}
           (quil/sprite :player 10 20
                        :width 32 :height 48 :tint [255 0 0])))))

(deftest asset-resolution-is-explicit
  (let [image (Object.)]
    (is (identical? image (quil/resolve-asset {:player image} :player)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (quil/resolve-asset {} :missing)))))
