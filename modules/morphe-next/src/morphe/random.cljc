(ns morphe.random
  "Deterministic xoshiro128** random state for Clojure and ClojureScript.")

(def ^:private uint-range 4294967296)
(def ^:private uint-mask 4294967295)
(def ^:private min-seed -2147483648)

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :random))))

(defn- unsigned
  [value]
  #?(:clj (bit-and (long value) uint-mask)
     :cljs (unsigned-bit-shift-right value 0)))

(defn- signed
  [value]
  #?(:clj (unchecked-int (long value))
     :cljs (bit-or value 0)))

(defn- add-word
  [left right]
  (unsigned (+ (long left) (long right))))

(defn- multiply-word
  [left right]
  (unsigned
    #?(:clj (unchecked-multiply-int (signed left) (signed right))
       :cljs (js/Math.imul (signed left) (signed right)))))

(defn- xor-word
  [left right]
  (unsigned (bit-xor (signed left) (signed right))))

(defn- shift-left
  [value amount]
  (unsigned (bit-shift-left (signed value) amount)))

(defn- shift-right
  [value amount]
  (unsigned-bit-shift-right (signed value) amount))

(defn- rotate-left
  [value amount]
  (unsigned (bit-or (signed (shift-left value amount))
                    (signed (shift-right value (- 32 amount))))))

(defn- mix-word
  [value]
  (let [first-mix (multiply-word (xor-word value (shift-right value 16))
                                 0x21f0aaad)
        second-mix (multiply-word (xor-word first-mix
                                            (shift-right first-mix 15))
                                  0x735a2d97)]
    (xor-word second-mix (shift-right second-mix 15))))

(defn- valid-words?
  [words]
  (and (vector? words)
       (= 4 (count words))
       (every? #(and (integer? %) (<= 0 % uint-mask)) words)
       (some pos? words)))

(defn- valid-seed?
  [seed]
  (and (integer? seed)
       (<= min-seed seed uint-mask)))

(defn from-words
  "Creates random state from four unsigned 32-bit words that are not all zero."
  [words]
  (when-not (valid-words? words)
    (fail! "Random state requires four unsigned 32-bit words that are not all zero"
           {:value words}))
  {:morphe.random/version 1 :words words})

(defn create
  "Creates deterministic random state from a signed or unsigned 32-bit seed."
  [seed]
  (when-not (valid-seed? seed)
    (fail! "Random seed must fit a signed or unsigned 32-bit integer"
           {:value seed}))
  (let [initial (unsigned seed)
        words (loop [value initial words []]
                (if (= 4 (count words))
                  words
                  (let [next-value (add-word value 0x9e3779b9)]
                    (recur next-value (conj words (mix-word next-value))))))]
    (from-words (if (some pos? words) words [1 0 0 0]))))

(defn- validate-state!
  [{version :morphe.random/version :keys [words] :as state}]
  (when-not (and (map? state) (= 1 version) (valid-words? words))
    (fail! "Random state is malformed" {:value state}))
  state)

(defn next-uint
  "Returns [next-state unsigned-32-bit-value]."
  [state]
  (let [[s0 s1 s2 s3] (:words (validate-state! state))
        result (multiply-word (rotate-left (multiply-word s1 5) 7) 9)
        temporary (shift-left s1 9)
        next-s2 (xor-word s2 s0)
        next-s3 (xor-word s3 s1)
        next-s1 (xor-word s1 next-s2)
        next-s0 (xor-word s0 next-s3)
        final-s2 (xor-word next-s2 temporary)
        final-s3 (rotate-left next-s3 11)]
    [(from-words [next-s0 next-s1 final-s2 final-s3]) result]))

(defn next-int
  "Returns [next-state value] where value is between zero and bound exclusive."
  [state bound]
  (when-not (and (pos-int? bound) (<= bound 2147483647))
    (fail! "Random integer bound must be between 1 and 2147483647"
           {:value bound}))
  (let [limit (* (quot uint-range bound) bound)]
    (loop [current state]
      (let [[next-state value] (next-uint current)]
        (if (< value limit)
          [next-state (mod value bound)]
          (recur next-state))))))

(defn next-double
  "Returns [next-state value] where value is in [0.0, 1.0)."
  [state]
  (let [[next-state value] (next-uint state)]
    [next-state (/ (double value) uint-range)]))
