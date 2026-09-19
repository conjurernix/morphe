(ns morphe.replay
  "Versioned recording and deterministic playback of fixed Morphe ticks."
  (:require [morphe.core :as game]
            [morphe.internal.event :as event]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :replay))))

(declare edn-data?)

(defn- edn-map?
  [value]
  (and (map? value)
       (not (record? value))
       (every? (fn [[key item]]
                 (and (edn-data? key) (edn-data? item)))
               value)))

(defn- edn-data?
  [value]
  (or (nil? value)
      (boolean? value)
      (string? value)
      (keyword? value)
      (symbol? value)
      (number? value)
      (char? value)
      (and (vector? value) (every? edn-data? value))
      (and (list? value) (every? edn-data? value))
      (and (set? value) (every? edn-data? value))
      (edn-map? value)))

(defn- valid-snapshot?
  [{version :morphe.snapshot/version :keys [context entities] :as snapshot}]
  (and (map? snapshot)
       (= 1 version)
       (map? context)
       (vector? entities)
       (edn-data? snapshot)))

(defn- validate-snapshot!
  [snapshot field]
  (when-not (valid-snapshot? snapshot)
    (fail! "Replay snapshots must be versioned EDN data"
           {:field field :value snapshot}))
  snapshot)

(defn- validate-tick!
  [{:keys [tick events] :as tick-record} expected-tick]
  (when-not (and (map? tick-record)
                 (= expected-tick tick)
                 (vector? events)
                 (every? event/event? events)
                 (edn-data? tick-record))
    (fail! "Replay tick is malformed or out of sequence"
           {:expected-tick expected-tick :value tick-record}))
  tick-record)

(defn start-recording
  "Creates replay recording state from an initial snapshot and update rate."
  [initial-snapshot update-hz]
  (validate-snapshot! initial-snapshot :initial-snapshot)
  (when-not (pos-int? update-hz)
    (fail! "Replay update rate must be a positive integer"
           {:value update-hz}))
  {:morphe.replay/version 1
   :update-hz update-hz
   :initial-snapshot initial-snapshot
   :ticks []})

(defn- validate-recording!
  [{version :morphe.replay/version
    :keys [update-hz initial-snapshot ticks]
    :as recording}]
  (when-not (and (map? recording)
                 (= 1 version)
                 (pos-int? update-hz)
                 (valid-snapshot? initial-snapshot)
                 (vector? ticks)
                 (edn-data? recording))
    (fail! "Replay recording is malformed or has an unsupported version"
           {:value recording}))
  (reduce (fn [expected tick-record]
            (validate-tick! tick-record expected)
            (inc expected))
          1
          ticks)
  recording)

(defn record-ticks
  "Appends ordered fixed-step tick records to a recording."
  [recording tick-records]
  (let [{:keys [ticks] :as recording} (validate-recording! recording)]
    (when-not (sequential? tick-records)
      (fail! "Replay tick records must be sequential"
             {:value tick-records}))
    (let [first-tick (inc (count ticks))
          added (mapv (fn [offset tick-record]
                        (validate-tick! tick-record (+ first-tick offset)))
                      (range)
                      tick-records)]
      (update recording :ticks into added))))

(defn finish-recording
  "Attaches the expected final snapshot and returns EDN-safe replay data."
  [recording final-snapshot]
  (validate-recording! recording)
  (validate-snapshot! final-snapshot :final-snapshot)
  (assoc recording :final-snapshot final-snapshot))

(defn play
  "Restores and plays a finished recording, or throws when its snapshot differs."
  [{:keys [update-hz initial-snapshot ticks final-snapshot] :as recording}
   factories sources]
  (validate-recording! recording)
  (validate-snapshot! final-snapshot :final-snapshot)
  (let [step-seconds (/ 1.0 update-hz)
        initial-game (game/restore initial-snapshot factories)
        result (reduce (fn [{:keys [game effects]} {:keys [events]}]
                         (let [step-result (game/step game sources
                                                      step-seconds events)]
                           {:game (:game step-result)
                            :effects (into effects (:effects step-result))}))
                       {:game initial-game :effects []}
                       ticks)
        actual-snapshot (game/snapshot (:game result))]
    (when-not (= final-snapshot actual-snapshot)
      (fail! "Replay final snapshot does not match"
             {:expected final-snapshot :actual actual-snapshot}))
    (assoc result :snapshot actual-snapshot)))
