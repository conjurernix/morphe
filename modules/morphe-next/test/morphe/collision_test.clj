(ns morphe.collision-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.collision :as collision]))

(def colliders
  [{:id :left :x 16 :y 16 :width 24 :height 24}
   {:id :center :x 80 :y 16 :width 24 :height 24}
   {:id :lower :x 80 :y 80 :width 24 :height 24}])

(deftest aabbs-have-explicit-edge-behavior
  (is (collision/overlaps? {:x 10 :y 10 :width 20 :height 20}
                           {:x 20 :y 10 :width 20 :height 20}))
  (is (not (collision/overlaps? {:x 10 :y 10 :width 20 :height 20}
                                {:x 30 :y 10 :width 20 :height 20})))
  (is (collision/contains-point? {:x 10 :y 10 :width 20 :height 20}
                                 {:x 0 :y 10}))
  (is (not (collision/contains-point? {:x 10 :y 10 :width 20 :height 20}
                                      {:x -0.1 :y 10}))))

(deftest spatial-queries-return-overlapping-colliders-in-insertion-order
  (let [index (collision/create-index 32 colliders)]
    (is (= [:left :center]
           (mapv :id (collision/query index
                                      {:x 48 :y 16 :width 80 :height 24}))))
    (is (= [:lower]
           (mapv :id (collision/query index
                                      {:x 80 :y 80 :width 20 :height 20}))))))

(deftest spatial-index-rejects-ambiguous-or-malformed-colliders
  (testing "duplicate identities"
    (is (thrown? clojure.lang.ExceptionInfo
                 (collision/create-index 32 [{:id :wall :x 0 :y 0
                                              :width 10 :height 10}
                                             {:id :wall :x 20 :y 0
                                              :width 10 :height 10}]))))
  (testing "invalid bounds"
    (is (thrown? clojure.lang.ExceptionInfo
                 (collision/create-index 32 [{:id :wall :x 0 :y 0
                                              :width 0 :height 10}])))))

(deftest raycasts-return-nearest-colliders-and-hit-points
  (let [index (collision/create-index 32 colliders)
        hits (collision/raycast index {:x 0 :y 16} {:x 1 :y 0} 100)]
    (is (= [:left :center] (mapv #(get-in % [:collider :id]) hits)))
    (is (= [4.0 68.0] (mapv :distance hits)))
    (is (= {:x 4.0 :y 16.0} (:point (collision/first-hit
                                      index {:x 0 :y 16} {:x 1 :y 0} 100))))))
