(ns morphe.adapters.lwjgl.audio
  "OpenAL static-sound and streamed Ogg Vorbis playback."
  (:require [morphe.adapters.lwjgl.assets :as assets])
  (:import [java.nio ByteBuffer IntBuffer ShortBuffer]
           [org.lwjgl.openal AL AL10 ALC ALC10]
           [org.lwjgl.stb STBVorbis STBVorbisInfo]
           [org.lwjgl.system MemoryStack MemoryUtil]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :audio))))

(defn- finite-number?
  [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- check-error!
  [operation]
  (let [error-code (AL10/alGetError)]
    (when-not (= AL10/AL_NO_ERROR error-code)
      (fail! "OpenAL operation failed"
             {:operation operation :error-code error-code}))))

(defn sound
  "Creates a static Ogg Vorbis sound descriptor."
  [path]
  (when-not (and (string? path) (not (empty? path)))
    (fail! "Sound assets require a classpath resource or filesystem path"
           {:path path}))
  {:morphe.audio/type :sound :path path})

(defn music
  "Creates a streamed Ogg Vorbis music descriptor."
  [path]
  (assoc (sound path) :morphe.audio/type :music))

(defn- normalize-sound
  [spec]
  (cond
    (string? spec) (sound spec)
    (and (map? spec) (= :sound (:morphe.audio/type spec)))
    (sound (:path spec))
    :else (fail! "Unsupported audio asset descriptor" {:value spec})))

(defn- normalize-music
  [spec]
  (cond
    (string? spec) (music spec)
    (and (map? spec) (= :music (:morphe.audio/type spec)))
    (music (:path spec))
    :else (fail! "Unsupported music asset descriptor" {:value spec})))

(defn- normalize-groups
  [groups]
  (when-not (and (map? groups)
                 (every? keyword? (keys groups))
                 (every? #(and (finite-number? %) (<= 0.0 %)) (vals groups)))
    (fail! "Audio groups require keyword names and nonnegative volumes"
           {:groups groups}))
  (merge {:master 1.0} groups))

(defn- with-direct-bytes
  [bytes f]
  (let [encoded (MemoryUtil/memAlloc (alength bytes))]
    (try
      (.put ^ByteBuffer encoded ^bytes bytes)
      (.flip ^ByteBuffer encoded)
      (f encoded)
      (finally
        (MemoryUtil/memFree encoded)))))

(defn- audio-format
  [channels]
  (case channels
    1 AL10/AL_FORMAT_MONO16
    2 AL10/AL_FORMAT_STEREO16
    (fail! "OpenAL supports mono or stereo sound assets"
           {:channels channels})))

(defn- load-buffer!
  [{:keys [path]}]
  (with-direct-bytes
    (assets/read-bytes! path)
    (fn [encoded]
      (with-open [stack (MemoryStack/stackPush)]
        (let [channels (.mallocInt stack 1)
              sample-rate (.mallocInt stack 1)
              ^ShortBuffer samples
              (STBVorbis/stb_vorbis_decode_memory
                encoded channels sample-rate)]
          (when (nil? samples)
            (fail! "Unable to decode Ogg Vorbis sound" {:path path}))
          (try
            (let [buffer-id (AL10/alGenBuffers)]
              (try
                (AL10/alBufferData buffer-id
                                   (audio-format (.get channels 0))
                                   samples
                                   (.get sample-rate 0))
                (check-error! :load-buffer)
                buffer-id
                (catch Throwable cause
                  (AL10/alDeleteBuffers buffer-id)
                  (throw cause))))
            (finally
              (MemoryUtil/memFree samples))))))))

(defn- open-device!
  []
  (let [^ByteBuffer device-name nil
        device (ALC10/alcOpenDevice device-name)]
    (when (zero? device)
      (fail! "Unable to open the default OpenAL device" {}))
    device))

(defn- create-context!
  [device]
  (let [^IntBuffer attributes nil
        capabilities (ALC/createCapabilities device)
        context (ALC10/alcCreateContext device attributes)]
    (when (zero? context)
      (fail! "Unable to create an OpenAL context" {}))
    (when-not (ALC10/alcMakeContextCurrent context)
      (ALC10/alcDestroyContext context)
      (fail! "Unable to activate the OpenAL context" {}))
    (try
      (AL/createCapabilities capabilities)
      context
      (catch Throwable cause
        (ALC10/alcMakeContextCurrent 0)
        (ALC10/alcDestroyContext context)
        (throw cause)))))

(declare destroy!)
(declare stop-music!)
(declare close-music-stream!)

(defn create!
  "Opens OpenAL and loads static sounds. Music descriptors stream on demand."
  ([sound-specs]
   (create! sound-specs {}))
  ([sound-specs {:keys [max-voices music groups]
                 :or {max-voices 32 music {} groups {}}}]
   (when-not (and (map? sound-specs) (map? music) (pos-int? max-voices))
     (fail! "Audio assets require maps and a positive voice limit"
            {:sounds sound-specs :music music :max-voices max-voices}))
   (let [device (open-device!)
         audio (atom {:device device :context 0 :buffers {} :sources {}
                      :source-order [] :max-voices max-voices
                      :music-specs (into {} (map (fn [[key spec]]
                                                    [key (normalize-music spec)])
                                                  music))
                      :music nil
                      :groups (normalize-groups groups)})]
     (try
       (swap! audio assoc :context (create-context! device))
       (let [by-spec (atom {})]
         (doseq [[sound-key raw-spec] sound-specs]
           (let [spec (normalize-sound raw-spec)
                 buffer-id (or (@by-spec spec)
                               (let [created (load-buffer! spec)]
                                 (swap! by-spec assoc spec created)
                                 created))]
             (swap! audio assoc-in [:buffers sound-key] buffer-id))))
       audio
       (catch Throwable cause
         (destroy! audio)
         (throw cause))))))

(defn- delete-source!
  [audio source-id]
  (AL10/alSourceStop source-id)
  (AL10/alDeleteSources source-id)
  (swap! audio
         (fn [state]
           (-> state
               (update :sources dissoc source-id)
               (update :source-order
                       #(into [] (remove #{source-id}) %))))))

(defn- reclaim-sources!
  [audio]
  (doseq [[source-id _] (:sources @audio)
          :when (contains? #{AL10/AL_STOPPED AL10/AL_INITIAL}
                           (AL10/alGetSourcei source-id AL10/AL_SOURCE_STATE))]
    (delete-source! audio source-id)))

(defn- enforce-voice-limit!
  [audio]
  (when (>= (count (:sources @audio)) (:max-voices @audio))
    (delete-source! audio (first (:source-order @audio)))))

(defn play!
  "Plays a loaded sound. Options support :volume, :pitch, and :loop?."
  ([audio sound-key]
   (play! audio sound-key {}))
  ([audio sound-key {:keys [volume pitch loop? group]
                     :or {volume 1.0 pitch 1.0 loop? false group :master}
                     :as options}]
   (let [buffer-id (get-in @audio [:buffers sound-key])]
     (when-not buffer-id
       (fail! "No loaded sound matches the key" {:sound sound-key}))
     (when-not (and (finite-number? volume) (<= 0.0 volume)
                    (finite-number? pitch) (pos? pitch)
                    (boolean? loop?)
                    (contains? (:groups @audio) group))
       (fail! "Sound playback options are invalid"
              {:sound sound-key :options options}))
     (reclaim-sources! audio)
     (enforce-voice-limit! audio)
     (let [source-id (AL10/alGenSources)]
       (try
         (AL10/alSourcei source-id AL10/AL_BUFFER buffer-id)
         (AL10/alSourcef source-id AL10/AL_GAIN
                          (float (* volume (get-in @audio [:groups group]))))
         (AL10/alSourcef source-id AL10/AL_PITCH (float pitch))
         (AL10/alSourcei source-id AL10/AL_LOOPING
                         (if loop? AL10/AL_TRUE AL10/AL_FALSE))
         (AL10/alSourcePlay source-id)
         (check-error! :play)
         (swap! audio
                (fn [state]
                  (-> state
                      (assoc-in [:sources source-id]
                                {:sound sound-key :volume volume :group group})
                      (update :source-order conj source-id))))
         source-id
         (catch Throwable cause
           (AL10/alDeleteSources source-id)
           (throw cause)))))))

(def ^:private stream-buffer-count 4)
(def ^:private stream-frames 4096)

(defn- open-music-stream!
  [spec group volume loop? seek-seconds]
  (let [bytes (assets/read-bytes! (:path spec))
        data (MemoryUtil/memAlloc (alength bytes))
        stream (atom {:decoder 0 :data data :scratch nil :buffers [] :source-id 0})]
    (try
      (.put ^ByteBuffer data ^bytes bytes)
      (.flip data)
      (with-open [stack (MemoryStack/stackPush)]
        (let [error (.mallocInt stack 1)
              decoder (STBVorbis/stb_vorbis_open_memory data error nil)]
          (when (zero? decoder)
            (fail! "Unable to open streamed music" {:path (:path spec)
                                                      :error (.get error 0)}))
          (swap! stream assoc :decoder decoder)
          (let [info (STBVorbisInfo/malloc stack)]
            (STBVorbis/stb_vorbis_get_info decoder info)
            (let [channels (.channels info)
                  sample-rate (.sample_rate info)
                  sample-offset (long (* seek-seconds sample-rate))]
              (when-not (and (contains? #{1 2} channels)
                             (<= 0 sample-offset)
                             (<= sample-offset Integer/MAX_VALUE)
                             (STBVorbis/stb_vorbis_seek decoder (int sample-offset)))
                (fail! "Unable to seek streamed music" {:path (:path spec)
                                                         :seconds seek-seconds}))
              (swap! stream assoc
                     :scratch (MemoryUtil/memAllocShort (* stream-frames channels))
                     :channels channels
                     :sample-rate sample-rate
                     :format (audio-format channels)
                     :group group
                     :volume volume
                     :loop? loop?
                     :paused? false)
              (dotimes [_ stream-buffer-count]
                (let [buffer-id (AL10/alGenBuffers)]
                  (swap! stream update :buffers conj buffer-id)))
              (swap! stream assoc :source-id (AL10/alGenSources))
              (check-error! :open-music-stream)
              @stream))))
      (catch Throwable cause
        (close-music-stream! @stream)
        (throw cause)))))

(defn- close-music-stream!
  [{:keys [source-id buffers decoder data scratch]}]
  (when (pos? source-id)
    (AL10/alSourceStop source-id)
    (AL10/alDeleteSources source-id))
  (doseq [buffer-id buffers]
    (AL10/alDeleteBuffers buffer-id))
  (when (pos? decoder) (STBVorbis/stb_vorbis_close decoder))
  (when scratch (MemoryUtil/memFree scratch))
  (when data (MemoryUtil/memFree data)))

(defn- refill-music-buffer!
  [{:keys [decoder channels scratch format sample-rate loop?]} buffer-id]
  (.clear ^ShortBuffer scratch)
  (let [frames (STBVorbis/stb_vorbis_get_samples_short_interleaved
                decoder channels scratch)]
    (if (zero? frames)
      (when (and loop? (STBVorbis/stb_vorbis_seek_start decoder))
        (recur {:decoder decoder :channels channels :scratch scratch
                :format format :sample-rate sample-rate :loop? false}
               buffer-id))
      (do
        (.limit ^ShortBuffer scratch (* frames channels))
        (.position ^ShortBuffer scratch 0)
        (AL10/alBufferData buffer-id format scratch sample-rate)
        true))))

(defn- queue-music!
  [stream]
  (doseq [buffer-id (:buffers stream)
          :while (refill-music-buffer! stream buffer-id)]
    (AL10/alSourceQueueBuffers (:source-id stream) buffer-id))
  stream)

(defn play-music!
  "Starts a streamed music asset. Options support :group, :volume, :loop?, and :seek-seconds."
  ([audio music-key]
   (play-music! audio music-key {}))
  ([audio music-key {:keys [group volume loop? seek-seconds]
                     :or {group :master volume 1.0 loop? true seek-seconds 0.0}
                     :as options}]
   (let [spec (get-in @audio [:music-specs music-key])]
     (when-not (and spec (contains? (:groups @audio) group)
                    (finite-number? volume) (<= 0.0 volume)
                    (boolean? loop?) (finite-number? seek-seconds)
                    (<= 0.0 seek-seconds))
       (fail! "Music playback options are invalid"
              {:music music-key :options options}))
     (let [stream (assoc (open-music-stream! spec group volume loop? seek-seconds)
                         :key music-key)]
       (try
         (queue-music! stream)
         (when (zero? (AL10/alGetSourcei (:source-id stream) AL10/AL_BUFFERS_QUEUED))
           (fail! "Streamed music contains no audio samples" {:music music-key}))
         (stop-music! audio)
         (AL10/alSourcef (:source-id stream) AL10/AL_GAIN
                          (float (* volume (get-in @audio [:groups group]))))
         (AL10/alSourcePlay (:source-id stream))
         (check-error! :play-music)
         (swap! audio assoc :music stream)
         (:source-id stream)
         (catch Throwable cause
           (close-music-stream! stream)
           (throw cause)))))))

(defn stop-music!
  "Stops and releases the active streamed music, if any."
  [audio]
  (when-let [stream (:music @audio)]
    (close-music-stream! stream)
    (swap! audio assoc :music nil))
  nil)

(defn seek-music!
  "Restarts active music at a nonnegative offset in seconds."
  [audio seconds]
  (when-not (and (finite-number? seconds) (<= 0.0 seconds))
    (fail! "Music seek offsets must be nonnegative finite seconds" {:seconds seconds}))
  (if-let [{:keys [key group volume loop?]} (:music @audio)]
    (play-music! audio key {:group group :volume volume :loop? loop?
                            :seek-seconds seconds})
    (fail! "No streamed music is active" {})))

(defn update!
  "Refills processed music buffers. Call once per adapter frame."
  [audio]
  (when-let [stream (:music @audio)]
    (doseq [_ (range (AL10/alGetSourcei (:source-id stream) AL10/AL_BUFFERS_PROCESSED))]
      (let [buffer-id (AL10/alSourceUnqueueBuffers (:source-id stream))]
        (when (refill-music-buffer! stream buffer-id)
          (AL10/alSourceQueueBuffers (:source-id stream) buffer-id))))
    (let [queued (AL10/alGetSourcei (:source-id stream) AL10/AL_BUFFERS_QUEUED)
          state (AL10/alGetSourcei (:source-id stream) AL10/AL_SOURCE_STATE)]
      (cond
        (zero? queued) (stop-music! audio)
        (and (not (:paused? stream)) (= AL10/AL_STOPPED state))
        (AL10/alSourcePlay (:source-id stream)))))
  nil)

(defn pause!
  "Pauses every active source."
  [audio]
  (doseq [source-id (keys (:sources @audio))]
    (AL10/alSourcePause source-id))
  (when-let [music (:music @audio)]
    (AL10/alSourcePause (:source-id music))
    (swap! audio assoc-in [:music :paused?] true))
  nil)

(defn resume!
  "Resumes every paused source."
  [audio]
  (doseq [source-id (keys (:sources @audio))
          :when (= AL10/AL_PAUSED
                   (AL10/alGetSourcei source-id AL10/AL_SOURCE_STATE))]
    (AL10/alSourcePlay source-id))
  (when-let [music (:music @audio)]
    (when (:paused? music)
      (AL10/alSourcePlay (:source-id music))
      (swap! audio assoc-in [:music :paused?] false)))
  nil)

(defn stop!
  "Stops and deletes every active source."
  [audio]
  (doseq [source-id (keys (:sources @audio))]
    (delete-source! audio source-id))
  (stop-music! audio)
  nil)

(defn set-group-volume!
  "Sets a group's gain and updates every active source assigned to it."
  [audio group-key requested-volume]
  (when-not (and (contains? (:groups @audio) group-key)
                 (finite-number? requested-volume) (<= 0.0 requested-volume))
    (fail! "Audio group and volume are invalid"
           {:group group-key :volume requested-volume}))
  (swap! audio assoc-in [:groups group-key] requested-volume)
  (doseq [[source-id {:keys [group volume]}] (:sources @audio)
          :when (= group-key group)]
    (AL10/alSourcef source-id AL10/AL_GAIN
                     (float (* volume requested-volume))))
  (when-let [{music-group :group music-volume :volume source-id :source-id}
             (:music @audio)]
    (when (= group-key music-group)
      (AL10/alSourcef source-id AL10/AL_GAIN
                       (float (* music-volume requested-volume)))))
  nil)

(defn set-volume!
  "Sets the OpenAL listener gain."
  [audio volume]
  (when-not (and (finite-number? volume) (<= 0.0 volume))
    (fail! "Audio volume must be nonnegative" {:volume volume}))
  (when (zero? (:context @audio))
    (fail! "Audio context is closed" {}))
  (AL10/alListenerf AL10/AL_GAIN (float volume))
  (check-error! :listener-volume)
  nil)

(defn destroy!
  "Stops playback and releases every source, buffer, context, and device."
  [audio]
  (let [{:keys [device context buffers]} @audio]
    (try
      (when (pos? context)
        (stop! audio)
        (doseq [buffer-id (distinct (vals buffers))]
          (AL10/alDeleteBuffers buffer-id)))
      (finally
        (when (pos? context)
          (ALC10/alcMakeContextCurrent 0)
          (ALC10/alcDestroyContext context))
        (when (pos? device)
          (ALC10/alcCloseDevice device))
        (reset! audio {:device 0 :context 0 :buffers {} :sources {}
                       :source-order [] :max-voices 0 :music-specs {}
                       :music nil :groups {}}))))
  nil)
