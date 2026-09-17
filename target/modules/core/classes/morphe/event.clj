(ns morphe.event)

(defn event?
  [value]
  (and (map? value)
       (keyword? (:type value))))

(defn event
  ([type]
   (event type {}))
  ([type payload]
   (when-not (keyword? type)
     (throw (ex-info "Event types must be keywords" {:type type})))
   (when-not (map? payload)
     (throw (ex-info "Event payloads must be maps" {:payload payload})))
   (assoc payload :type type)))

(defn normalize
  [events]
  (cond
    (nil? events) []
    (event? events) [events]
    (sequential? events)
    (mapv
      (fn [value]
        (when-not (event? value)
          (throw (ex-info "Event collections may contain only events" {:value value})))
        value)
      events)
    :else (throw (ex-info "Events must be an event or sequential collection"
                          {:events events}))))
