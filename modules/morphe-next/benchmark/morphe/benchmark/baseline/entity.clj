(ns morphe.benchmark.baseline.entity
  (:require [clojure.set :as set]))

(defprotocol Component
  (component-key [component])
  (update-component [component state context message]))

(defprotocol Entity
  (update-entity [entity context message])
  (render-entity [entity context render-context]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :validation))))

(defn- handler?
  [value]
  (or (fn? value) (instance? clojure.lang.MultiFn value)))

(defn- changed-keys
  [before after]
  (let [keys (set/union (set (keys before)) (set (keys after)))
        missing (Object.)]
    (into #{}
          (filter #(not= (get before % missing) (get after % missing)))
          keys)))

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

(defrecord ComponentInstance [key handler]
  Component
  (component-key [_]
    key)
  (update-component [_ state context message]
    (let [[next-state outputs]
          (validate-handler-result
            (handler state context message)
            {:component-key key :message message})
          unauthorized (disj (changed-keys state next-state) key)]
      (when (seq unauthorized)
        (fail! "A component may change only the state under its own key"
               {:component-key key
                :changed-keys unauthorized
                :message message}))
      [next-state outputs])))

(defrecord EntityInstance [state components handler render-handler]
  Entity
  (update-entity [entity context message]
    (let [[next-state outputs]
          (validate-handler-result
            (handler state context message)
            {:handler :entity :message message})
          owned-keys (set (map component-key components))
          changed-owned-keys (set/intersection owned-keys
                                               (changed-keys state next-state))]
      (when (seq changed-owned-keys)
        (fail! "An entity handler may not change component-owned state"
               {:changed-keys changed-owned-keys :message message}))
      (let [[final-state final-outputs]
            (reduce
              (fn [[current-state current-outputs] component]
                (let [[component-state component-outputs]
                      (update-component component current-state context message)]
                  [component-state (into current-outputs component-outputs)]))
              [next-state outputs]
              components)]
        [(assoc entity :state final-state) final-outputs])))
  (render-entity [_ context render-context]
    (if render-handler
      (let [render-data (render-handler state context render-context)]
        (when-not (sequential? render-data)
          (fail! "Render handlers must return sequential data"
                 {:handler :render :value render-data}))
        (vec render-data))
      [])))

(defn component?
  [value]
  (satisfies? Component value))

(defn entity?
  [value]
  (satisfies? Entity value))

(defn component
  [key handler]
  (when-not (and (keyword? key) (namespace key))
    (fail! "Component keys must be namespace-qualified keywords" {:key key}))
  (when-not (handler? handler)
    (fail! "Component handlers must be functions" {:key key :value handler}))
  (->ComponentInstance key handler))

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
     (->EntityInstance state components handler render-handler))))

(defn state
  [{:keys [state]}]
  state)

(defn components
  [{:keys [components]}]
  components)

