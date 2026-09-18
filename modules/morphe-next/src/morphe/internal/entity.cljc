(ns morphe.internal.entity)

(def ^:private MISSING #?(:clj (Object.) :cljs (js-obj)))

(defrecord ComponentDescriptor [key handler])
(defrecord EntityDescriptor
  [state components handler render-handler owned-keys])

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :validation))))

(defn- handler?
  [value]
  #?(:clj (or (fn? value) (instance? clojure.lang.MultiFn value))
     :cljs (fn? value)))

(defn- changed?
  [before after key]
  (not= (get before key MISSING) (get after key MISSING)))

(defn- changed-keys
  [before after]
  (let [keys (into (set (keys before)) (keys after))]
    (into #{} (filter #(changed? before after %)) keys)))

(defn- changed-owned-keys
  [before after owned-keys]
  (reduce (fn [changed key]
            (if (changed? before after key)
              (conj changed key)
              changed))
          #{}
          owned-keys))

(defn- unchanged-entry?
  [other key value]
  (= value (get other key MISSING)))

(defn- unchanged-except-key?
  [before after owned-key]
  (and
    (reduce-kv (fn [_ key value]
                 (if (or (= owned-key key)
                         (unchanged-entry? after key value))
                   true
                   (reduced false)))
               true
               before)
    (reduce-kv (fn [_ key value]
                 (if (or (= owned-key key)
                         (unchanged-entry? before key value))
                   true
                   (reduced false)))
               true
               after)))

(defn- validate-handler-result
  [result data]
  (when-not (and (vector? result) (= 2 (count result)))
    (fail! "Handlers must return [new-state outputs]" (assoc data :value result)))
  (let [[state outputs] result]
    (when-not (map? state)
      (fail! "Handler state must be a map" (assoc data :value state)))
    (when-not (sequential? outputs)
      (fail! "Handler outputs must be sequential" (assoc data :value outputs)))
    [state (vec outputs)]))

(defn component?
  [value]
  (instance? ComponentDescriptor value))

(defn entity?
  [value]
  (instance? EntityDescriptor value))

(defn component-key
  [{:keys [key]}]
  key)

(defn component
  [key handler]
  (when-not (and (keyword? key) (namespace key))
    (fail! "Component keys must be namespace-qualified keywords" {:key key}))
  (when-not (handler? handler)
    (fail! "Component handlers must be functions" {:key key :value handler}))
  (->ComponentDescriptor key handler))

(defn entity
  ([state components handler]
   (entity state components handler nil))
  ([state components handler render-handler]
   (when-not (map? state)
     (fail! "Entity state must be a map" {:value state}))
   (when-not (sequential? components)
     (fail! "Entity components must be sequential" {:value components}))
   (when-not (every? component? components)
     (fail! "Entity components must be created with component" {:value components}))
   (when-not (handler? handler)
     (fail! "Entity update handler must be a function" {:value handler}))
   (when (and render-handler (not (handler? render-handler)))
     (fail! "Entity render handler must be a function" {:value render-handler}))
   (let [components (vec components)
         keys (mapv component-key components)]
     (when-not (= (count keys) (count (distinct keys)))
       (fail! "Entity component keys must be unique" {:component-keys keys}))
     (->EntityDescriptor state components handler render-handler (set keys)))))

(defn- update-component
  [state context message {:keys [key handler]}]
  (let [[next-state outputs]
        (validate-handler-result
          (handler state context message)
          {:component-key key :message message})]
    (when-not (unchanged-except-key? state next-state key)
      (fail! "A component may change only the state under its own key"
             {:component-key key
              :changed-keys (disj (changed-keys state next-state) key)
              :message message}))
    [next-state outputs]))

(defn update-entity
  [{:keys [state components handler owned-keys] :as entity}
   context message]
  (let [[next-state outputs]
        (validate-handler-result
          (handler state context message)
          {:handler :entity :message message})
        changed-owned (changed-owned-keys state next-state owned-keys)]
    (when (seq changed-owned)
      (fail! "An entity handler may not change component-owned state"
             {:changed-keys changed-owned :message message}))
    ;; The indexed loop avoids accumulator tuples and component seqs in this profiled hot path.
    (loop [index 0
           current-state next-state
           current-outputs outputs]
      (if (= index (count components))
        [(assoc entity :state current-state) current-outputs]
        (let [[component-state component-outputs]
              (update-component current-state context message
                                (nth components index))]
          (recur (inc index)
                 component-state
                 (into current-outputs component-outputs)))))))

(defn render-entity
  [{:keys [state render-handler]} context render-context]
  (if render-handler
    (let [render-data (render-handler state context render-context)]
      (when-not (sequential? render-data)
        (fail! "Render handlers must return sequential data"
               {:handler :render :value render-data}))
      (vec render-data))
    []))

(defn state
  [{:keys [state]}]
  state)

(defn components
  [{:keys [components]}]
  components)
