(ns morphe.command)

(defn command?
  [value]
  (and (map? value)
       (keyword? (:type value))))

(defn command
  ([type]
   (command type {}))
  ([type payload]
   (when-not (keyword? type)
     (throw (ex-info "Command types must be keywords" {:type type})))
   (when-not (map? payload)
     (throw (ex-info "Command payloads must be maps" {:payload payload})))
   (assoc payload :type type)))

(defn normalize
  [commands]
  (cond
    (nil? commands) []
    (command? commands) [commands]
    (sequential? commands)
    (mapv
      (fn [value]
        (when-not (command? value)
          (throw (ex-info "Command collections may contain only commands"
                          {:value value})))
        value)
      commands)
    :else (throw (ex-info "Commands must be a command or sequential collection"
                          {:commands commands}))))
