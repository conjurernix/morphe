(ns morphe.runtime
  (:require [morphe.command :as command]
            [morphe.system :as system]))

(defn runtime
  [configuration]
  (let [{:keys [command-systems systems]}
        (if (vector? configuration)
          {:command-systems [] :systems configuration}
          configuration)
        command-systems (vec (or command-systems []))
        systems (vec (or systems []))]
    (when-not (every? #(satisfies? system/CommandProcessor %) command-systems)
      (throw (ex-info "A runtime accepts only command systems in :command-systems"
                      {:systems command-systems})))
    (when-not (every? #(satisfies? system/ExecutableSystem %) systems)
      (throw (ex-info "A runtime accepts only executable systems in :systems"
                      {:systems systems})))
    {:command-systems command-systems :systems systems}))

(defn- append-system-result
  [{:keys [events]} result]
  (let [{next-world :world new-events :events} result]
    {:world next-world :events (into events new-events)}))

(defn step
  ([runtime world dt]
   (step runtime world dt []))
  ([runtime world dt commands]
   (let [commands (command/normalize commands)
         command-result
         (reduce
           (fn [result current-system]
             (append-system-result
               result
               (system/process-commands current-system (:world result) dt commands)))
           {:world world :events []}
           (:command-systems runtime))]
     (reduce
       (fn [result current-system]
         (append-system-result
           result
           (system/execute-system current-system (:world result) dt)))
       command-result
       (:systems runtime)))))
