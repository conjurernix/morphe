(ns platformer.sound
  (:import [java.util.concurrent Executors]
           [javax.sound.sampled AudioFormat AudioSystem Clip]))

(def tones {:jump 440.0 :coin 880.0 :land 220.0 :hit 110.0
            :shoot 660.0 :enemy-hit 150.0})
(def sample-rate 22050)
(def duration 0.09)
(def ^:private clips (atom {}))
(def ^:private playback-executor
  (Executors/newSingleThreadExecutor))

(defn- tone-bytes
  [frequency]
  (let [samples (int (* sample-rate duration))
        buffer (byte-array samples)]
    (dotimes [index samples]
      (let [envelope (- 1.0 (/ index samples))
            sample (Math/sin (* 2.0 Math/PI frequency (/ index sample-rate)))]
        (aset-byte buffer index (byte (* 110 envelope sample)))))
    buffer))

(defn- create-clip
  [frequency]
  (let [format (AudioFormat. sample-rate 8 1 true false)
        clip (AudioSystem/getClip)]
    (.open ^Clip clip format (tone-bytes frequency) 0 (int (* sample-rate duration)))
    clip))

(defn start!
  []
  (reset! clips
          (reduce-kv (fn [loaded tone frequency]
                       (assoc loaded tone (create-clip frequency)))
                     {}
                     tones)))

(defn play-tone!
  "Starts a preloaded tone without blocking the game loop."
  [tone]
  (.execute playback-executor ^Runnable
             (fn []
               (when-let [clip (get @clips tone)]
                 (locking clip
                   (.setFramePosition ^Clip clip 0)
                   (.start ^Clip clip))))))

(defn stop!
  []
  (.shutdownNow playback-executor)
  (doseq [clip (vals @clips)]
    (.close ^Clip clip))
  (reset! clips {}))
