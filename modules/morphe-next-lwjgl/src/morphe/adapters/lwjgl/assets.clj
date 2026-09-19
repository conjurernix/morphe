(ns morphe.adapters.lwjgl.assets
  "Classpath and filesystem image and font assets for the LWJGL adapter."
  (:require [clojure.java.io :as io])
  (:import [java.io ByteArrayOutputStream]
           [java.nio ByteBuffer]
           [org.lwjgl.opengl GL11 GL12 GL30]
           [org.lwjgl.stb STBImage STBTTBakedChar STBTruetype]
           [org.lwjgl.system MemoryStack MemoryUtil]))

(def image-filters
  #{:linear :nearest})

(def image-wrap-modes
  #{:clamp :repeat})

(def ^:private first-font-codepoint 32)
(def ^:private font-glyph-count 95)

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :asset))))

(defn image
  "Creates an image asset descriptor for a classpath resource or filesystem path."
  [path & {:keys [filter wrap]
           :or {filter :linear wrap :clamp}}]
  (when-not (and (string? path)
                 (not (empty? path))
                 (contains? image-filters filter)
                 (contains? image-wrap-modes wrap))
    (fail! "Image assets require a path, filter, and wrap mode"
           {:path path :filter filter :wrap wrap}))
  {:morphe.asset/type :image
   :path path
   :filter filter
   :wrap wrap})

(defn font
  "Creates a static ASCII TrueType font descriptor.

  The font atlas contains codepoints 32 through 126 at the requested pixel size."
  [path size & {:keys [atlas-size filter]
                :or {atlas-size 512 filter :linear}}]
  (when-not (and (string? path)
                 (not (empty? path))
                 (number? size)
                 (Double/isFinite (double size))
                 (pos? size)
                 (pos-int? atlas-size)
                 (contains? image-filters filter))
    (fail! "Font assets require a path, positive size, atlas size, and filter"
           {:path path :size size :atlas-size atlas-size :filter filter}))
  {:morphe.asset/type :font
   :path path
   :size size
   :atlas-size atlas-size
   :filter filter})

(defn canvas
  "Creates an off-screen RGBA render target descriptor."
  [width height & {:keys [filter]
                   :or {filter :linear}}]
  (when-not (and (pos-int? width)
                 (pos-int? height)
                 (contains? image-filters filter))
    (fail! "Canvas assets require positive dimensions and a texture filter"
           {:width width :height height :filter filter}))
  {:morphe.asset/type :canvas
   :width width
   :height height
   :filter filter})

(defn- normalize-spec
  [spec]
  (cond
    (string? spec) (image spec)
    (and (map? spec) (= :image (:morphe.asset/type spec)))
    (image (:path spec) :filter (:filter spec) :wrap (:wrap spec))
    (and (map? spec) (= :font (:morphe.asset/type spec)))
    (font (:path spec) (:size spec)
          :atlas-size (:atlas-size spec) :filter (:filter spec))
    (and (map? spec) (= :canvas (:morphe.asset/type spec)))
    (canvas (:width spec) (:height spec) :filter (:filter spec))
    :else (fail! "Unsupported LWJGL asset descriptor" {:value spec})))

(defn- open-stream!
  [path]
  (let [resource (io/resource path)
        file (io/file path)]
    (cond
      resource (io/input-stream resource)
      (.isFile file) (io/input-stream file)
      :else (fail! "Asset resource does not exist" {:path path}))))

(defn read-bytes!
  "Reads a classpath resource or filesystem path into a byte array."
  [path]
  (with-open [input (open-stream! path)
              output (ByteArrayOutputStream.)]
    (io/copy input output)
    (.toByteArray output)))

(defn- with-direct-bytes
  [bytes f]
  (let [encoded (MemoryUtil/memAlloc (alength bytes))]
    (try
      (.put ^ByteBuffer encoded ^bytes bytes)
      (.flip ^ByteBuffer encoded)
      (f encoded)
      (finally
        (MemoryUtil/memFree encoded)))))

(defn- texture-filter
  [filter]
  (case filter
    :nearest GL11/GL_NEAREST
    :linear GL11/GL_LINEAR))

(defn- texture-wrap
  [wrap]
  (case wrap
    :repeat GL11/GL_REPEAT
    :clamp GL12/GL_CLAMP_TO_EDGE))

(defn- upload-texture!
  [width height pixels filter wrap]
  (let [texture-id (GL11/glGenTextures)]
    (try
      (GL11/glBindTexture GL11/GL_TEXTURE_2D texture-id)
      (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MIN_FILTER
                            (texture-filter filter))
      (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MAG_FILTER
                            (texture-filter filter))
      (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_S
                            (texture-wrap wrap))
      (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_T
                            (texture-wrap wrap))
      (GL11/glTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGBA8 width height 0
                         GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE pixels)
      texture-id
      (catch Throwable cause
        (GL11/glDeleteTextures texture-id)
        (throw cause)))))

(defn- upload-image!
  [{:keys [path filter wrap] :as spec}]
  (with-direct-bytes
    (read-bytes! path)
    (fn [encoded]
      (with-open [stack (MemoryStack/stackPush)]
        (let [width (.mallocInt stack 1)
              height (.mallocInt stack 1)
              channels (.mallocInt stack 1)
              pixels (STBImage/stbi_load_from_memory
                       encoded width height channels 4)]
          (when (nil? pixels)
            (fail! "Unable to decode image asset"
                   {:path path :reason (STBImage/stbi_failure_reason)}))
          (try
            {:morphe.asset/type :image
             :texture-id (upload-texture! (.get width 0) (.get height 0)
                                           pixels filter wrap)
             :width (.get width 0)
             :height (.get height 0)
             :source spec}
            (finally
              (STBImage/stbi_image_free pixels))))))))

(defn- glyph-data
  [glyph]
  {:x0 (int (.x0 glyph))
   :y0 (int (.y0 glyph))
   :x1 (int (.x1 glyph))
   :y1 (int (.y1 glyph))
   :xoff (.xoff glyph)
   :yoff (.yoff glyph)
   :xadvance (.xadvance glyph)})

(defn- rgba-font-pixels
  [bitmap atlas-size]
  (let [pixels (MemoryUtil/memAlloc (* 4 atlas-size atlas-size))]
    (dotimes [index (* atlas-size atlas-size)]
      (let [alpha (.get ^ByteBuffer bitmap index)]
        (.put pixels (byte -1))
        (.put pixels (byte -1))
        (.put pixels (byte -1))
        (.put pixels alpha)))
    (.flip pixels)
    pixels))

(defn- upload-font!
  [{:keys [path size atlas-size filter] :as spec}]
  (with-direct-bytes
    (read-bytes! path)
    (fn [font-data]
      (let [bitmap (MemoryUtil/memCalloc (* atlas-size atlas-size))
            glyphs (STBTTBakedChar/malloc font-glyph-count)]
        (try
          (let [baked-height (STBTruetype/stbtt_BakeFontBitmap
                               font-data (float size) bitmap atlas-size atlas-size
                               first-font-codepoint glyphs)]
            (when-not (pos? baked-height)
              (fail! "Font atlas is too small for its requested size"
                     {:path path :size size :atlas-size atlas-size}))
            (let [pixels (rgba-font-pixels bitmap atlas-size)]
              (try
                {:morphe.asset/type :font
                 :texture-id (upload-texture! atlas-size atlas-size pixels filter :clamp)
                 :width atlas-size
                 :height atlas-size
                 :line-height size
                 :glyphs (into {}
                               (map (fn [index]
                                      [(char (+ first-font-codepoint index))
                                       (glyph-data (.get glyphs index))]))
                               (range font-glyph-count))
                 :source spec}
                (finally
                  (MemoryUtil/memFree pixels)))))
          (finally
            (.free glyphs)
            (MemoryUtil/memFree bitmap)))))))

(defn- upload-canvas!
  [{:keys [width height filter] :as spec}]
  (let [^ByteBuffer pixels nil
        texture-id (upload-texture! width height pixels filter :clamp)
        framebuffer-id (GL30/glGenFramebuffers)]
    (try
      (GL30/glBindFramebuffer GL30/GL_FRAMEBUFFER framebuffer-id)
      (GL30/glFramebufferTexture2D GL30/GL_FRAMEBUFFER
                                   GL30/GL_COLOR_ATTACHMENT0
                                   GL11/GL_TEXTURE_2D texture-id 0)
      (when-not (= GL30/GL_FRAMEBUFFER_COMPLETE
                   (GL30/glCheckFramebufferStatus GL30/GL_FRAMEBUFFER))
        (fail! "Unable to create a complete canvas framebuffer"
               {:width width :height height}))
      {:morphe.asset/type :canvas
       :texture-id texture-id
       :framebuffer-id framebuffer-id
       :width width
       :height height
       :source spec}
      (catch Throwable cause
        (GL30/glDeleteFramebuffers framebuffer-id)
        (GL11/glDeleteTextures texture-id)
        (throw cause))
      (finally
        (GL30/glBindFramebuffer GL30/GL_FRAMEBUFFER 0)))))

(defn- upload-asset!
  [spec]
  (case (:morphe.asset/type spec)
    :image (upload-image! spec)
    :font (upload-font! spec)
    :canvas (upload-canvas! spec)))

(defn load-assets!
  "Loads image, font, and canvas descriptors once and returns keyed assets."
  [asset-specs]
  (when-not (map? asset-specs)
    (fail! "LWJGL assets must be a map" {:value asset-specs}))
  (let [loaded (atom {})
        by-spec (atom {})]
    (try
      (doseq [[asset-key raw-spec] asset-specs]
        (let [spec (normalize-spec raw-spec)
              asset (or (@by-spec spec)
                        (let [created (upload-asset! spec)]
                          (swap! by-spec assoc spec created)
                          created))]
          (swap! loaded assoc asset-key asset)))
      @loaded
      (catch Throwable cause
        (doseq [framebuffer-id (->> (vals @by-spec)
                                    (map :framebuffer-id)
                                    (remove nil?)
                                    distinct)]
          (GL30/glDeleteFramebuffers framebuffer-id))
        (doseq [texture-id (distinct (map :texture-id (vals @by-spec)))]
          (GL11/glDeleteTextures texture-id))
        (throw cause)))))

(defn load-images!
  "Loads image, font, and canvas descriptors. Kept for source compatibility."
  [asset-specs]
  (load-assets! asset-specs))

(defn destroy-images!
  "Deletes every native texture and framebuffer in an asset map."
  [assets]
  (doseq [framebuffer-id (->> (vals assets)
                              (map :framebuffer-id)
                              (remove nil?)
                              distinct)]
    (GL30/glDeleteFramebuffers framebuffer-id))
  (doseq [texture-id (->> (vals assets)
                          (map :texture-id)
                          (remove nil?)
                          distinct)]
    (GL11/glDeleteTextures texture-id))
  nil)

(defn destroy-assets!
  "Deletes every native texture and framebuffer in an asset map."
  [assets]
  (destroy-images! assets))

(defn get-asset
  "Returns an asset by key or throws structured asset data."
  [assets asset-key]
  (or (get assets asset-key)
      (fail! "No loaded LWJGL asset matches the key" {:asset asset-key})))
