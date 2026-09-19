(ns morphe.random-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.random :as random]))

(deftest xoshiro-reference-state-produces-known-values
  (let [[state first-value] (random/next-uint (random/from-words [1 2 3 4]))
        [_ second-value] (random/next-uint state)]
    (is (= 11520 first-value))
    (is (= [7 0 1026 12288] (:words state)))
    (is (= 0 second-value))))

(deftest equal-seeds-produce-equal-sequences
  (letfn [(values [seed]
            (loop [state (random/create seed) values []]
              (if (= 20 (count values))
                values
                (let [[next-state value] (random/next-uint state)]
                  (recur next-state (conj values value))))))]
    (is (= (values 42) (values 42)))
    (is (not= (values 42) (values 43)))))

(deftest bounded-values-respect-their-contracts
  (let [[state integer] (random/next-int (random/create 7) 6)
        [_ fraction] (random/next-double state)]
    (is (<= 0 integer 5))
    (is (<= 0.0 fraction))
    (is (< fraction 1.0))))

(deftest random-input-is-validated
  (testing "state"
    (is (thrown? clojure.lang.ExceptionInfo
                 (random/from-words [0 0 0 0])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (random/next-uint {:words [1 2 3 4]}))))
  (testing "seed and bound"
    (is (thrown? clojure.lang.ExceptionInfo (random/create 1.5)))
    (is (thrown? clojure.lang.ExceptionInfo (random/create 4294967296)))
    (is (thrown? clojure.lang.ExceptionInfo (random/create -2147483649)))
    (is (= (random/create -1) (random/create 4294967295)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (random/next-int (random/create 1) 0)))))
