(ns morphe.query
  (:require [morphe.world :as world]))

(def ^:private clause-type ::clause)

(defn without [& component-keys]
  {clause-type :without :keys (set component-keys)})

(defn optional [& component-keys]
  {clause-type :optional :keys (set component-keys)})

(defn all [& clauses]
  (reduce
    (fn [query clause]
      (cond
        (keyword? clause) (update query :required conj clause)
        (= :without (clause-type clause)) (update query :excluded into (:keys clause))
        (= :optional (clause-type clause)) (update query :optional into (:keys clause))
        :else (throw (ex-info "Query clauses must be keywords or query clauses"
                              {:clause clause}))))
    {:required #{} :excluded #{} :optional #{} }
    clauses))

(defn- matching? [world entity-id {:keys [required excluded]}]
  (and (every? #(contains? (world/component-store world %) entity-id) required)
       (not-any? #(contains? (world/component-store world %) entity-id) excluded)))

(defn query [world query-value]
  (->> (world/entities world)
       (filter #(matching? world % query-value))
       sort
       vec))
