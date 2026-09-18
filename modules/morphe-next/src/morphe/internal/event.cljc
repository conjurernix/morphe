(ns morphe.internal.event
  (:refer-clojure :exclude [send]))

(def send-tag ::send)
(def broadcast-tag ::broadcast)
(def effect-tag ::effect)

(defn- fail!
  [message value]
  (throw (ex-info message {:phase :validation :value value})))

(defn message?
  [message]
  (and (vector? message)
       (let [[message-type] message]
         (keyword? message-type))))

(defn effect-value?
  [effect-value]
  (and (vector? effect-value)
       (let [[effect-type] effect-value]
         (keyword? effect-type))))

(defn event?
  [{:keys [message] :as value}]
  (and (map? value)
       (contains? value :target)
       (message? message)))

(defn send
  [target message]
  (when (nil? target)
    (fail! "A targeted message requires a non-nil entity ID" target))
  (when-not (message? message)
    (fail! "Entity messages must be vectors beginning with a keyword" message))
  [send-tag target message])

(defn broadcast
  [message]
  (when-not (message? message)
    (fail! "Entity messages must be vectors beginning with a keyword" message))
  [broadcast-tag message])

(defn effect
  [effect-value]
  (when-not (effect-value? effect-value)
    (fail! "Effects must be vectors beginning with a keyword" effect-value))
  [effect-tag effect-value])

(defn event
  ([message]
   (event nil message))
  ([target message]
   (when-not (message? message)
     (fail! "Entity messages must be vectors beginning with a keyword" message))
   {:target target :message message}))
