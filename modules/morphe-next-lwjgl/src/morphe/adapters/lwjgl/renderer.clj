(ns morphe.adapters.lwjgl.renderer
  "Buffered OpenGL renderer for Morphe 2D render data."
  (:import [java.nio ByteBuffer FloatBuffer]
           [org.lwjgl.opengl GL11 GL15 GL20 GL30]
           [org.lwjgl.system MemoryStack MemoryUtil]))

(def ^:private floats-per-vertex 8)
(def ^:private initial-buffer-floats 4096)
(def ^:private circle-segments 32)

(def ^:private vertex-shader
  "#version 120
   attribute vec2 aPosition;
   attribute vec4 aColor;
   attribute vec2 aUv;
   uniform vec2 uViewport;
   varying vec4 vColor;
   varying vec2 vUv;
   void main() {
     vec2 unit = aPosition / uViewport;
     vec2 clip = unit * 2.0 - 1.0;
     gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
     vColor = aColor;
     vUv = aUv;
   }")

(def ^:private fragment-shader
  "#version 120
   uniform sampler2D uTexture;
   varying vec4 vColor;
   varying vec2 vUv;
   void main() {
     gl_FragColor = texture2D(uTexture, vUv) * vColor;
   }")

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :phase :render))))

(defn- finite-number?
  [value]
  (and (number? value) (Double/isFinite (double value))))

(defn normalize-color
  "Returns normalized RGBA values from three or four byte or normalized values."
  [value]
  (when-not (and (sequential? value)
                 (contains? #{3 4} (count value))
                 (every? #(and (finite-number? %) (<= 0.0 % 255.0)) value))
    (fail! "Colors require three or four numeric values" {:value value}))
  (let [rgba (if (= 3 (count value)) (conj (vec value) 1.0) (vec value))]
    (mapv #(if (> % 1.0) (/ (double %) 255.0) (double %)) rgba)))

(defn- vertex
  [x y [red green blue alpha] u v]
  [x y red green blue alpha u v])

(defn- validate-point!
  [point label]
  (when-not (and (sequential? point)
                 (= 2 (count point))
                 (every? finite-number? point))
    (fail! "Points require two finite numeric values" {:label label :value point}))
  point)

(defn- normalize-transform
  [transform]
  (when-not (or (nil? transform) (map? transform))
    (fail! "Transforms must be maps" {:value transform}))
  (let [{:keys [translate scale rotation origin]} (or transform {})
        [translate-x translate-y] (or translate [0.0 0.0])
        [scale-x scale-y] (or scale [1.0 1.0])
        [origin-x origin-y] (or origin [0.0 0.0])
        rotation (or rotation 0.0)]
    (doseq [[label point] [[:translate [translate-x translate-y]]
                           [:scale [scale-x scale-y]]
                           [:origin [origin-x origin-y]]]]
      (validate-point! point label))
    (when-not (finite-number? rotation)
      (fail! "Transform rotation must be finite" {:value rotation}))
    {:translate [translate-x translate-y]
     :scale [scale-x scale-y]
     :origin [origin-x origin-y]
     :rotation rotation}))

(defn- transform-point
  [[x y] {:keys [translate scale rotation origin]}]
  (let [[translate-x translate-y] translate
        [scale-x scale-y] scale
        [origin-x origin-y] origin
        local-x (* (- x origin-x) scale-x)
        local-y (* (- y origin-y) scale-y)
        cosine (Math/cos rotation)
        sine (Math/sin rotation)]
    [(+ origin-x translate-x
        (- (* local-x cosine) (* local-y sine)))
     (+ origin-y translate-y
        (* local-x sine) (* local-y cosine))]))

(defn- normalize-camera
  [camera]
  (when-not (or (nil? camera) (map? camera))
    (fail! "Cameras must be maps" {:value camera}))
  (let [{camera-x :x camera-y :y zoom :zoom
         :or {camera-x 0.0 camera-y 0.0 zoom 1.0}} (or camera {})]
    (when-not (and (every? finite-number? [camera-x camera-y zoom])
                   (pos? zoom))
      (fail! "Cameras require finite x, y, and positive zoom values"
             {:value camera}))
    {:x camera-x :y camera-y :zoom zoom}))

(defn- camera-point
  [[x y] {:keys [zoom] camera-x :x camera-y :y}]
  [(* (- x camera-x) zoom)
   (* (- y camera-y) zoom)])

(defn- transform-vertices
  [vertices transform camera]
  (let [transform (normalize-transform transform)
        camera (normalize-camera camera)]
    (mapv (fn [[x y red green blue alpha u v]]
            (let [[next-x next-y] (camera-point (transform-point [x y] transform)
                                                camera)]
              [next-x next-y red green blue alpha u v]))
          vertices)))

(defn- rectangle-vertices
  [{:keys [x y width height fill]}]
  (when-not (and (every? finite-number? [x y width height])
                 (pos? width) (pos? height))
    (fail! "Rectangle coordinates and size must be numeric"
           {:value {:x x :y y :width width :height height}}))
  (let [color (normalize-color fill)
        left (- x (/ width 2.0))
        right (+ x (/ width 2.0))
        top (- y (/ height 2.0))
        bottom (+ y (/ height 2.0))
        top-left (vertex left top color 0.0 0.0)
        top-right (vertex right top color 1.0 0.0)
        bottom-right (vertex right bottom color 1.0 1.0)
        bottom-left (vertex left bottom color 0.0 1.0)]
    (into [] cat [[top-left top-right bottom-right]
                  [top-left bottom-right bottom-left]])))

(defn- circle-vertices
  [{:keys [x y radius fill]}]
  (when-not (and (every? finite-number? [x y radius]) (pos? radius))
    (fail! "Circle coordinates and radius must be valid"
           {:value {:x x :y y :radius radius}}))
  (let [color (normalize-color fill)
        center (vertex x y color 0.5 0.5)]
    (into []
          cat
          (map (fn [index]
                 (let [first-angle (* 2.0 Math/PI (/ index circle-segments))
                       second-angle (* 2.0 Math/PI
                                       (/ (inc index) circle-segments))]
                   [center
                    (vertex (+ x (* radius (Math/cos first-angle)))
                            (+ y (* radius (Math/sin first-angle)))
                            color 0.0 0.0)
                    (vertex (+ x (* radius (Math/cos second-angle)))
                            (+ y (* radius (Math/sin second-angle)))
                            color 0.0 0.0)]))
               (range circle-segments)))))

(defn- line-vertices
  [{:keys [from to width fill]}]
  (validate-point! from :from)
  (validate-point! to :to)
  (when-not (and (finite-number? width) (pos? width))
    (fail! "Line width must be a positive finite number" {:width width}))
  (let [[from-x from-y] from
        [to-x to-y] to
        delta-x (- to-x from-x)
        delta-y (- to-y from-y)
        length (Math/hypot delta-x delta-y)]
    (when (zero? length)
      (fail! "Line endpoints must differ" {:from from :to to}))
    (let [half-width (/ width 2.0)
          offset-x (* (/ (- delta-y) length) half-width)
          offset-y (* (/ delta-x length) half-width)
          color (normalize-color fill)
          start-left (vertex (+ from-x offset-x) (+ from-y offset-y) color 0.0 0.0)
          start-right (vertex (- from-x offset-x) (- from-y offset-y) color 0.0 1.0)
          end-left (vertex (+ to-x offset-x) (+ to-y offset-y) color 1.0 0.0)
          end-right (vertex (- to-x offset-x) (- to-y offset-y) color 1.0 1.0)]
      [start-left end-left end-right start-left end-right start-right])))

(defn- signed-area
  [points]
  (/ (reduce +
             (map (fn [[[x1 y1] [x2 y2]]]
                    (- (* x1 y2) (* y1 x2)))
                  (partition 2 1 (conj (vec points) (first points)))))
     2.0))

(defn- cross-product
  [[ax ay] [bx by] [cx cy]]
  (- (* (- bx ax) (- cy ay))
     (* (- by ay) (- cx ax))))

(defn- point-in-triangle?
  [point first-point second-point third-point winding]
  (let [crosses [(cross-product first-point second-point point)
                 (cross-product second-point third-point point)
                 (cross-product third-point first-point point)]]
    (every? #(if (pos? winding) (not (neg? %)) (not (pos? %))) crosses)))

(defn- ear?
  [points indices position winding]
  (let [count-indices (count indices)
        previous-index (nth indices (mod (dec position) count-indices))
        current-index (nth indices position)
        next-index (nth indices (mod (inc position) count-indices))
        first-point (nth points previous-index)
        second-point (nth points current-index)
        third-point (nth points next-index)]
    (and (pos? (* winding (cross-product first-point second-point third-point)))
         (not-any? #(point-in-triangle? (nth points %)
                                        first-point second-point third-point winding)
                   (remove #{previous-index current-index next-index} indices)))))

(defn- triangulate-points
  [points]
  (let [points (vec points)
        winding (signed-area points)]
    (when (zero? winding)
      (fail! "Polygon points must enclose an area" {:points points}))
    (loop [indices (vec (range (count points))) triangles []]
      (if (= 3 (count indices))
        (conj triangles (mapv #(nth points %) indices))
        (let [ear-position (first (keep-indexed
                                   (fn [position _]
                                     (when (ear? points indices position winding)
                                       position))
                                   indices))]
          (when-not (some? ear-position)
            (fail! "Polygon must be simple and non-self-intersecting"
                   {:points points}))
          (let [count-indices (count indices)
                triangle [(nth points (nth indices (mod (dec ear-position) count-indices)))
                          (nth points (nth indices ear-position))
                          (nth points (nth indices (mod (inc ear-position) count-indices)))]]
            (recur (into [] (concat (subvec indices 0 ear-position)
                                    (subvec indices (inc ear-position))))
                   (conj triangles triangle))))))))

(defn- polygon-vertices
  [{:keys [points fill]}]
  (when-not (and (sequential? points) (<= 3 (count points)))
    (fail! "Polygons require at least three points" {:points points}))
  (doseq [point points]
    (validate-point! point :polygon-point))
  (let [color (normalize-color fill)]
    (into []
          (mapcat (fn [triangle]
                    (map #(apply vertex (concat % [color 0.0 0.0])) triangle)))
          (triangulate-points points))))

(defn- image-metadata
  [assets asset-key]
  (let [asset (get assets asset-key)]
    (when-not (and (map? asset)
                   (pos-int? (:texture-id asset))
                   (pos-int? (:width asset))
                   (pos-int? (:height asset)))
      (fail! "Sprite references an absent or malformed image asset"
             {:asset asset-key :value asset}))
    asset))

(defn- sprite-vertices
  [assets {:keys [asset x y width height tint region]}]
  (let [{image-width :width image-height :height :as image}
        (image-metadata assets asset)
        [region-x region-y region-width region-height]
        (or region [0 0 image-width image-height])
        width (or width region-width)
        height (or height region-height)]
    (when-not (and (every? finite-number? [x y width height
                                           region-x region-y region-width region-height])
                   (pos? width) (pos? height)
                   (not (neg? region-x)) (not (neg? region-y))
                   (pos? region-width) (pos? region-height)
                   (<= (+ region-x region-width) image-width)
                   (<= (+ region-y region-height) image-height))
      (fail! "Sprite coordinates, size, or atlas region are invalid"
             {:asset asset :region region :width width :height height}))
    (let [color (normalize-color (or tint [1.0 1.0 1.0 1.0]))
          left (- x (/ width 2.0))
          right (+ x (/ width 2.0))
          top (- y (/ height 2.0))
          bottom (+ y (/ height 2.0))
          u0 (/ region-x (double image-width))
          v0 (/ region-y (double image-height))
          u1 (/ (+ region-x region-width) (double image-width))
          v1 (/ (+ region-y region-height) (double image-height))
          top-left (vertex left top color u0 v0)
          top-right (vertex right top color u1 v0)
          bottom-right (vertex right bottom color u1 v1)
          bottom-left (vertex left bottom color u0 v1)]
      {:texture-id (:texture-id image)
       :vertices (into [] cat [[top-left top-right bottom-right]
                               [top-left bottom-right bottom-left]])})))

(defn- font-metadata
  [assets font-key]
  (let [font (get assets font-key)]
    (when-not (and (= :font (:morphe.asset/type font))
                   (pos-int? (:texture-id font))
                   (pos-int? (:width font))
                   (pos-int? (:height font))
                   (number? (:line-height font))
                   (pos? (:line-height font))
                   (map? (:glyphs font)))
      (fail! "Text references an absent or malformed font asset"
             {:font font-key :value font}))
    font))

(defn- text-vertices
  [assets {:keys [font text x y fill]}]
  (when-not (and (string? text) (every? finite-number? [x y]))
    (fail! "Text requires a string and finite x and y coordinates"
           {:text text :x x :y y}))
  (let [{:keys [texture-id width height line-height glyphs]} (font-metadata assets font)
        color (normalize-color fill)]
    {:texture-id texture-id
     :vertices
     (loop [characters (seq text)
            cursor-x x
            cursor-y y
            vertices []]
       (if-let [character (first characters)]
         (cond
           (= character \newline)
           (recur (next characters) x (+ cursor-y line-height) vertices)

           (= character \return)
           (recur (next characters) cursor-x cursor-y vertices)

           :else
           (let [{:keys [x0 y0 x1 y1 xoff yoff xadvance] :as glyph}
                 (get glyphs character)]
             (when-not glyph
               (fail! "Font does not contain a requested text character"
                      {:font font :character character}))
             (let [left (+ cursor-x xoff)
                   right (+ left (- x1 x0))
                   top (+ cursor-y yoff)
                   bottom (+ top (- y1 y0))
                   u0 (/ x0 (double width))
                   v0 (/ y0 (double height))
                   u1 (/ x1 (double width))
                   v1 (/ y1 (double height))
                   top-left (vertex left top color u0 v0)
                   top-right (vertex right top color u1 v0)
                   bottom-right (vertex right bottom color u1 v1)
                   bottom-left (vertex left bottom color u0 v1)]
               (recur (next characters)
                      (+ cursor-x xadvance)
                      cursor-y
                      (into vertices [top-left top-right bottom-right
                                      top-left bottom-right bottom-left])))))
         vertices))}))

(defn- normalize-clip
  [clip]
  (when (some? clip)
    (when-not (and (sequential? clip)
                   (= 4 (count clip))
                   (every? integer? clip)
                   (pos? (nth clip 2))
                   (pos? (nth clip 3)))
      (fail! "Clip rectangles require integer x, y, width, and height values"
             {:value clip}))
    (let [[x y width height] clip]
      {:x x :y y :width width :height height})))

(defn- renderable-batch
  [assets camera {:keys [kind layer transform clip target] :as renderable}]
  (when-not (finite-number? (or layer 0))
    (fail! "Render layers must be finite numbers"
           {:layer layer :value renderable}))
  (let [batch (case kind
                :rectangle {:texture-id 0 :vertices (rectangle-vertices renderable)}
                :circle {:texture-id 0 :vertices (circle-vertices renderable)}
                :line {:texture-id 0 :vertices (line-vertices renderable)}
                :polygon {:texture-id 0 :vertices (polygon-vertices renderable)}
                :sprite (sprite-vertices assets renderable)
                :text (text-vertices assets renderable)
                (fail! "Unsupported LWJGL renderable" {:kind kind :value renderable}))]
    (-> batch
        (assoc :clip (normalize-clip clip))
        (assoc :target target)
        (update :vertices transform-vertices transform camera))))

(defn compile-batches
  "Sorts render data by layer and batches adjacent compatible triangles."
  ([assets renderables]
   (compile-batches assets renderables nil))
  ([assets renderables camera]
   (when-not (sequential? renderables)
     (fail! "Render data must be sequential" {:value renderables}))
   (reduce (fn [batches renderable]
             (let [{:keys [texture-id vertices clip target]} (renderable-batch assets camera renderable)
                   previous (peek batches)]
               (if (and (= texture-id (:texture-id previous))
                        (= clip (:clip previous))
                        (= target (:target previous)))
                 (conj (pop batches)
                      (update previous :vertices into vertices))
                 (conj batches {:texture-id texture-id
                                :clip clip
                                :target target
                                :vertices vertices}))))
           []
           (sort-by #(or (:layer %) 0) renderables))))

(defn- compile-shader!
  [shader-type source]
  (let [shader (GL20/glCreateShader shader-type)]
    (GL20/glShaderSource shader source)
    (GL20/glCompileShader shader)
    (when (zero? (GL20/glGetShaderi shader GL20/GL_COMPILE_STATUS))
      (let [log (GL20/glGetShaderInfoLog shader)]
        (GL20/glDeleteShader shader)
        (fail! "Unable to compile renderer shader" {:log log})))
    shader))

(defn- create-program!
  []
  (let [vertex (compile-shader! GL20/GL_VERTEX_SHADER vertex-shader)]
    (try
      (let [fragment (compile-shader! GL20/GL_FRAGMENT_SHADER fragment-shader)
            program (GL20/glCreateProgram)]
        (try
          (GL20/glAttachShader program vertex)
          (GL20/glAttachShader program fragment)
          (GL20/glBindAttribLocation program 0 "aPosition")
          (GL20/glBindAttribLocation program 1 "aColor")
          (GL20/glBindAttribLocation program 2 "aUv")
          (GL20/glLinkProgram program)
          (when (zero? (GL20/glGetProgrami program GL20/GL_LINK_STATUS))
            (fail! "Unable to link renderer shader"
                   {:log (GL20/glGetProgramInfoLog program)}))
          program
          (catch Throwable cause
            (GL20/glDeleteProgram program)
            (throw cause))
          (finally
            (GL20/glDeleteShader fragment))))
      (finally
        (GL20/glDeleteShader vertex)))))

(defn- create-white-texture!
  []
  (with-open [stack (MemoryStack/stackPush)]
    (let [^ByteBuffer pixel (.malloc stack 4)
          texture-id (GL11/glGenTextures)]
      (dotimes [_ 4]
        (.put pixel (byte -1)))
      (.flip pixel)
      (try
        (GL11/glBindTexture GL11/GL_TEXTURE_2D texture-id)
        (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MIN_FILTER
                              GL11/GL_NEAREST)
        (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MAG_FILTER
                              GL11/GL_NEAREST)
        (GL11/glTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGBA8 1 1 0
                           GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE pixel)
        texture-id
        (catch Throwable cause
          (GL11/glDeleteTextures texture-id)
          (throw cause))))))

(defn- destroy-resources!
  [{:keys [program vbo white-texture buffer]}]
  (when buffer (MemoryUtil/memFree buffer))
  (when (pos? (or white-texture 0)) (GL11/glDeleteTextures white-texture))
  (when (pos? (or vbo 0)) (GL15/glDeleteBuffers vbo))
  (when (pos? (or program 0)) (GL20/glDeleteProgram program)))

(defn- create-resource!
  [resources resource-key create-fn]
  (let [resource (create-fn)]
    (swap! resources assoc resource-key resource)
    resource))

(defn create!
  "Creates reusable shader, VBO, and native vertex-buffer state."
  []
  (let [resources (atom {})]
    (try
      (let [program (create-resource! resources :program create-program!)
            vbo (create-resource! resources :vbo #(GL15/glGenBuffers))
            _white-texture (create-resource! resources :white-texture
                                             create-white-texture!)
            _buffer (create-resource!
                      resources :buffer
                      #(MemoryUtil/memAllocFloat initial-buffer-floats))
            viewport-location (GL20/glGetUniformLocation program "uViewport")
            texture-location (GL20/glGetUniformLocation program "uTexture")]
        (when (or (neg? viewport-location) (neg? texture-location))
          (fail! "Renderer shader uniforms are unavailable" {}))
        (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo)
        (GL15/glBufferData GL15/GL_ARRAY_BUFFER
                           (* initial-buffer-floats Float/BYTES)
                           GL15/GL_STREAM_DRAW)
        (atom (assoc @resources
                     :viewport-location viewport-location
                     :texture-location texture-location
                     :capacity initial-buffer-floats)))
      (catch Throwable cause
        (destroy-resources! @resources)
        (throw cause)))))

(defn- next-capacity
  [required]
  (loop [capacity initial-buffer-floats]
    (if (>= capacity required)
      capacity
      (recur (* 2 capacity)))))

(defn- ensure-capacity!
  [renderer required]
  (let [{:keys [capacity]} @renderer]
    (when (> required capacity)
      (let [next-size (next-capacity required)
            next-buffer (MemoryUtil/memAllocFloat next-size)
            previous-buffer (:buffer @renderer)]
        (try
          (GL15/glBufferData GL15/GL_ARRAY_BUFFER
                             (* next-size Float/BYTES)
                             GL15/GL_STREAM_DRAW)
          (swap! renderer assoc :buffer next-buffer :capacity next-size)
          (MemoryUtil/memFree previous-buffer)
          (catch Throwable cause
            (MemoryUtil/memFree next-buffer)
            (throw cause)))))))

(defn- upload-vertices!
  [renderer vertices]
  (let [required (count vertices)]
    (ensure-capacity! renderer required)
    (let [^FloatBuffer buffer (:buffer @renderer)]
      (.clear buffer)
      (doseq [value vertices]
        (.put buffer (float value)))
      (.flip buffer)
      (GL15/glBufferSubData GL15/GL_ARRAY_BUFFER 0 buffer))))

(defn- apply-clip!
  [viewport-width viewport-height clip]
  (if-not clip
    (GL11/glDisable GL11/GL_SCISSOR_TEST)
    (let [{:keys [x y width height]} clip
          left (max 0 x)
          top (max 0 y)
          right (min viewport-width (+ x width))
          bottom (min viewport-height (+ y height))]
      (GL11/glEnable GL11/GL_SCISSOR_TEST)
      (GL11/glScissor left
                      (max 0 (- viewport-height bottom))
                      (max 0 (- right left))
                      (max 0 (- bottom top))))))

(defn- render-target
  [assets target screen-width screen-height]
  (if-not target
    {:framebuffer-id 0 :width screen-width :height screen-height}
    (let [canvas (get assets target)]
      (when-not (and (= :canvas (:morphe.asset/type canvas))
                     (pos-int? (:framebuffer-id canvas))
                     (pos-int? (:width canvas))
                     (pos-int? (:height canvas)))
        (fail! "Render target references an absent or malformed canvas"
               {:target target :value canvas}))
      canvas)))

(defn- bind-target!
  [assets target screen-width screen-height]
  (let [{:keys [framebuffer-id width height] :as target-data}
        (render-target assets target screen-width screen-height)]
    (GL30/glBindFramebuffer GL30/GL_FRAMEBUFFER framebuffer-id)
    (GL11/glViewport 0 0 width height)
    target-data))

(defn- clear-canvas!
  []
  (GL11/glClearColor 0.0 0.0 0.0 0.0)
  (GL11/glClear GL11/GL_COLOR_BUFFER_BIT))

(defn render!
  "Draws compiled batches with one buffered draw call per compatible run.

  The optional asset map resolves `:target` canvas keys in compiled batches."
  ([renderer width height batches]
   (render! renderer width height batches {}))
  ([renderer width height batches assets]
   (when-not (and (pos-int? width) (pos-int? height)
                  (sequential? batches) (map? assets))
     (fail! "Renderer requires a positive viewport, batches, and asset map"
            {:width width :height height :batches batches :assets assets}))
   (let [{:keys [program vbo white-texture viewport-location texture-location]}
         @renderer
         stride (* floats-per-vertex Float/BYTES)
         cleared-targets (atom #{})]
     (GL11/glEnable GL11/GL_BLEND)
     (GL11/glBlendFunc GL11/GL_SRC_ALPHA GL11/GL_ONE_MINUS_SRC_ALPHA)
     (GL20/glUseProgram program)
     (GL20/glUniform1i texture-location 0)
     (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo)
     (doseq [attribute (range 3)]
       (GL20/glEnableVertexAttribArray attribute))
     (GL20/glVertexAttribPointer 0 2 GL11/GL_FLOAT false stride 0)
     (GL20/glVertexAttribPointer 1 4 GL11/GL_FLOAT false stride
                                 (* 2 Float/BYTES))
     (GL20/glVertexAttribPointer 2 2 GL11/GL_FLOAT false stride
                                 (* 6 Float/BYTES))
     (doseq [{:keys [texture-id vertices clip target]} batches]
       (let [{target-width :width target-height :height}
             (bind-target! assets target width height)]
         (when (and target (not (contains? @cleared-targets target)))
           (clear-canvas!)
           (swap! cleared-targets conj target))
         (GL20/glUniform2f viewport-location
                           (float target-width) (float target-height))
         (apply-clip! target-width target-height clip)
         (upload-vertices! renderer (into [] cat vertices))
         (GL11/glBindTexture GL11/GL_TEXTURE_2D
                             (if (zero? texture-id) white-texture texture-id))
         (GL11/glDrawArrays GL11/GL_TRIANGLES 0 (count vertices))))
     (doseq [attribute (range 3)]
       (GL20/glDisableVertexAttribArray attribute))
     (GL11/glDisable GL11/GL_SCISSOR_TEST)
     (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
     (GL20/glUseProgram 0)
     (GL30/glBindFramebuffer GL30/GL_FRAMEBUFFER 0)
     (GL11/glViewport 0 0 width height))
   nil))

(defn destroy!
  "Releases renderer GPU and native memory."
  [renderer]
  (destroy-resources! @renderer)
  (reset! renderer {})
  nil)
