(ns snake-event.components.position
  (:require [morphe.core :as game]))

(def position-key :snake-event.components/position)

(defn component
  [initial-position]
  (game/component position-key
    (fn [state _context [message-type position reply-id reply-message]]
      (case message-type
        ::set
        [(assoc state position-key position)
         (cond-> []
           reply-id (conj (game/send reply-id reply-message)))]

        ::restart
        [(assoc state position-key (or position initial-position))
         (cond-> []
           reply-id (conj (game/send reply-id reply-message)))]

        [state []]))))
