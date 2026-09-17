(ns morphe.resource)

(defn get-resource
  ([world resource-key] (get-resource world resource-key nil))
  ([world resource-key default] (get-in world [:resources resource-key] default)))

(defn set-resource [world resource-key value]
  (assoc-in world [:resources resource-key] value))

(defn update-resource [world resource-key f & args]
  (apply update-in world [:resources resource-key] f args))

(defn remove-resource [world resource-key]
  (update world :resources dissoc resource-key))
