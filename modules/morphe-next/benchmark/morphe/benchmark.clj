(ns morphe.benchmark
  (:require [clj-async-profiler.core :as profiler]
            [criterium.core :as criterium]
            [criterium.stats :as stats]
            [morphe.benchmark.baseline :as baseline]
            [morphe.core :as data-oriented]))

(def baseline-api
  {:name :baseline
   :game baseline/game
   :entity baseline/entity
   :component baseline/component
   :add-entity baseline/add-entity
   :event baseline/event
   :step baseline/step
   :render-data baseline/render-data})

(def data-oriented-api
  {:name :data-oriented
   :game data-oriented/game
   :entity data-oriented/entity
   :component data-oriented/component
   :add-entity data-oriented/add-entity
   :event data-oriented/event
   :step data-oriented/step
   :render-data data-oriented/render-data})

(def entity-count 1000)
(def render-entity-count 10000)
(def event-count 10000)
(def broadcast-count 25)
(def component-count 4)

(def quick-options
  {:samples 10
   :warmup-jit-period 5000000000
   :target-execution-time 200000000
   :tail-quantile 0.01})

(def full-options
  {:samples 60
   :warmup-jit-period 10000000000
   :target-execution-time 1000000000
   :tail-quantile 0.01})

(defn- component-key
  [index]
  (keyword "morphe.benchmark.component" (str "value-" index)))

(defn- create-component
  [{:keys [component]} index]
  (let [key (component-key index)]
    (component key
               (fn [state _context [message-type]]
                 (if (= ::components message-type)
                   [(update state key inc) []]
                   [state []])))))

(defn- create-entity
  [{:keys [entity] :as api} components? render?]
  (let [components (if components?
                     (mapv #(create-component api %) (range component-count))
                     [])
        state (into {:updates 0}
                    (map (fn [index] [(component-key index) 0]))
                    (if components? (range component-count) []))
        update-handler (fn [current _context [message-type]]
                         (if (= ::update message-type)
                           [(update current :updates inc) []]
                           [current []]))
        render-handler (when render?
                         (fn [{:keys [updates]} _context _render-context]
                           [{:kind :benchmark :value updates}]))]
    (if render-handler
      (entity state components update-handler render-handler)
      (entity state components update-handler))))

(defn- create-game
  [{create-game :game :keys [add-entity] :as api} count components? render?]
  (reduce (fn [game-state id]
            (add-entity game-state id (create-entity api components? render?)))
          (create-game)
          (range count)))

(defn- targeted-fixture
  [{create-event :event :as api}]
  {:api api
   :game (create-game api entity-count false false)
   :events (mapv #(create-event (mod % entity-count) [::update])
                 (range event-count))})

(defn- broadcast-fixture
  [{create-event :event :as api} components?]
  {:api api
   :game (create-game api entity-count components? false)
   :events (vec (repeat broadcast-count
                        (create-event [(if components? ::components ::update)])))})

(defn- render-fixture
  [api]
  {:api api
   :game (create-game api render-entity-count false true)})

(defn run-events
  [{{:keys [step]} :api :keys [game events]}]
  (step game [] 0.016 events {:max-events 1000000}))

(defn run-render
  [{{:keys [render-data]} :api :keys [game]}]
  (render-data game))

(defn- fixtures
  [api]
  {:targeted {:run run-events :fixture (targeted-fixture api)}
   :broadcast {:run run-events :fixture (broadcast-fixture api false)}
   :components {:run run-events :fixture (broadcast-fixture api true)}
   :render {:run run-render :fixture (render-fixture api)}})

(defn- sample-seconds
  [{:keys [samples execution-count]}]
  (mapv #(/ (double %) execution-count 1.0e9) samples))

(defn- summarize
  [result]
  (let [samples (sort (sample-seconds result))]
    {:median-seconds (stats/quantile 0.5 samples)
     :p99-seconds (stats/quantile 0.99 samples)
     :standard-deviation-seconds (Math/sqrt (stats/variance samples))
     :samples (count samples)}))

(defn- benchmark-workload!
  [workload implementations options]
  (let [prepared (mapv #(get (fixtures %) workload) implementations)
        results (criterium/benchmark-round-robin*
                  (mapv (fn [{:keys [run fixture]}
                             {:keys [name]}]
                          {:f #(run fixture)
                           :expr-string (clojure.core/name name)})
                        prepared
                        implementations)
                  options)]
    (println (pr-str {:workload workload
                      :results (mapv (fn [{:keys [name]} result]
                                       (assoc (summarize result)
                                              :implementation name))
                                     implementations
                                     results)}))))

(defn- benchmark-all!
  [implementations quick?]
  (doseq [workload [:targeted :broadcast :components :render]]
    (benchmark-workload! workload implementations
                         (if quick? quick-options full-options))))

(defn- profile-implementation!
  [api event]
  (let [{:keys [run fixture]} (:components (fixtures api))]
    (profiler/profile {:event event}
      (dotimes [_ 100]
        (run fixture)))))

(defn -main
  [& arguments]
  (case (vec arguments)
    ["--quick"] (benchmark-all! [baseline-api data-oriented-api] true)
    ["--quick" "render"]
    (benchmark-workload! :render [baseline-api data-oriented-api] quick-options)
    ["--profile" "cpu"] (println (profile-implementation! baseline-api :cpu))
    ["--profile" "alloc"] (println (profile-implementation! baseline-api :alloc))
    ["--profile" "cpu" "data-oriented"]
    (println (profile-implementation! data-oriented-api :cpu))
    ["--profile" "alloc" "data-oriented"]
    (println (profile-implementation! data-oriented-api :alloc))
    [] (benchmark-all! [baseline-api data-oriented-api] false)
    (throw (ex-info "Use --quick, --profile cpu|alloc [data-oriented], or no arguments"
                    {:arguments arguments}))))
