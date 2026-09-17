(ns morphe.adapters.lwjgl
  (:require [clojure.set :as set]
            [morphe.components.2d :as c2d]
            [morphe.components.3d :as c3d]
            [morphe.components.render-2d :as render-2d]
            [morphe.components.render-3d :as render-3d]
            [morphe.core :as ecs]
            [morphe.query :as query])
  (:import [org.lwjgl.glfw GLFW]
           [org.lwjgl.opengl GL GL11 GL15 GL20 GL30]
           [org.lwjgl.stb STBImage]
           [org.lwjgl.system MemoryStack MemoryUtil]))

(defn press-key [pressed-keys key] (conj pressed-keys key))
(defn release-key [pressed-keys key] (disj pressed-keys key))

(defn color
  "Converts RGB values in either byte or normalized form to normalized RGB."
  [value]
  (mapv #(if (> % 1.0) (/ % 255.0) (double %)) (take 3 value)))

(defn drawable-entities
  "Returns all 2D and 3D render entities in stable layer order."
  [world]
  (->> (set/union (set (query/query world (query/all c2d/position-key)))
                  (set (query/query world (query/all c3d/position-key))))
       (keep (fn [entity]
               (let [shape (ecs/component world entity render-2d/shape-key)
                     sprite (ecs/component world entity render-2d/sprite-key)
                     cube (ecs/component world entity render-3d/cube-key)]
                 (when (or shape sprite cube)
                   {:entity entity
                    :position (ecs/component world entity c2d/position-key)
                    :position-3d (ecs/component world entity c3d/position-key)
                    :scale (ecs/component world entity c2d/scale-key)
                    :scale-3d (ecs/component world entity c3d/scale-key)
                    :rotation-3d (ecs/component world entity c3d/rotation-key)
                    :shape shape
                    :sprite sprite
                    :cube cube
                    :layer (or (:layer shape) (:layer sprite) (:layer cube) 0)}))))
       (sort-by (juxt :layer :entity))
       vec))

(defn cube-vertices
  "Returns 36 triangle vertices for a centered cube."
  [size]
  (let [h (/ size 2.0)
        points [[(- h) (- h) h] [h (- h) h] [h h h] [(- h) (- h) h] [h h h] [(- h) h h]
                [h (- h) (- h)] [(- h) (- h) (- h)] [(- h) h (- h)] [h (- h) (- h)] [(- h) h (- h)] [h h (- h)]
                [(- h) (- h) (- h)] [(- h) (- h) h] [(- h) h h] [(- h) (- h) (- h)] [(- h) h h] [(- h) h (- h)]
                [h (- h) h] [h (- h) (- h)] [h h (- h)] [h (- h) h] [h h (- h)] [h h h]
                [(- h) h h] [h h h] [h h (- h)] [(- h) h h] [h h (- h)] [(- h) h (- h)]
                [(- h) (- h) (- h)] [h (- h) (- h)] [h (- h) h] [(- h) (- h) (- h)] [h (- h) h] [(- h) (- h) h]]]
    (float-array (mapcat identity points))))

(defn validate-options
  [{:keys [command-fn event-fn overlay-fn] :as options}]
  (doseq [[key value] [[:command-fn command-fn] [:event-fn event-fn] [:overlay-fn overlay-fn]]]
    (when (and value (not (fn? value)))
      (throw (ex-info (str "LWJGL adapter " key " must be a function") {:option key}))))
  options)

(defn camera-for-world
  "Returns the first camera component, or the default origin camera."
  [world]
  (or (some #(ecs/component world % render-3d/camera-key)
            (query/query world (query/all render-3d/camera-key)))
      (render-3d/camera {})))

(defn- matrix-multiply [left right]
  (float-array
    (for [column (range 4)
          row (range 4)]
      (reduce + (for [index (range 4)]
                  (* (aget left (+ (* index 4) row))
                     (aget right (+ (* column 4) index))))))))

(defn- translation-matrix [[x y z]]
  (float-array [1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 x y z 1.0]))

(defn- scale-matrix [[x y z]]
  (float-array [x 0.0 0.0 0.0 0.0 y 0.0 0.0 0.0 0.0 z 0.0 0.0 0.0 0.0 1.0]))

(defn- quaternion-matrix [[x y z w]]
  (float-array [(- 1.0 (* 2.0 (+ (* y y) (* z z)))) (+ (* 2.0 (+ (* x y) (* w z)))) (+ (* 2.0 (- (* x z) (* w y)))) 0.0
                (+ (* 2.0 (- (* x y) (* w z)))) (- 1.0 (* 2.0 (+ (* x x) (* z z)))) (+ (* 2.0 (+ (* y z) (* w x)))) 0.0
                (+ (* 2.0 (+ (* x z) (* w y)))) (+ (* 2.0 (- (* y z) (* w x)))) (- 1.0 (* 2.0 (+ (* x x) (* y y)))) 0.0
                0.0 0.0 0.0 1.0]))

(defn- orthographic-matrix [width height]
  (float-array [(/ 2.0 width) 0.0 0.0 0.0 0.0 (/ -2.0 height) 0.0 0.0
                0.0 0.0 -1.0 0.0 -1.0 1.0 0.0 1.0]))

(defn- perspective-matrix [width height fov near far]
  (let [f (/ 1.0 (Math/tan (/ (* fov Math/PI) 360.0)))
        aspect (/ (double width) height)
        range (- near far)]
    (float-array [(/ f aspect) 0.0 0.0 0.0 0.0 f 0.0 0.0
                  0.0 0.0 (/ (+ far near) range) -1.0
                  0.0 0.0 (/ (* 2.0 far near) range) 0.0])))

(defn- view-matrix [{:keys [position rotation]}]
  (matrix-multiply
    (quaternion-matrix (let [[x y z w] (or rotation [0.0 0.0 0.0 1.0])] [(- x) (- y) (- z) w]))
    (translation-matrix (mapv - (or position [0.0 0.0 0.0])))))

(defn- shader! [type source]
  (let [shader (GL20/glCreateShader type)]
    (GL20/glShaderSource shader source)
    (GL20/glCompileShader shader)
    (when (= GL11/GL_FALSE (GL20/glGetShaderi shader GL20/GL_COMPILE_STATUS))
      (throw (ex-info "LWJGL shader compilation failed" {:log (GL20/glGetShaderInfoLog shader)})))
    shader))

(defn- program! []
  (let [program (GL20/glCreateProgram)
        vertex (shader! GL20/GL_VERTEX_SHADER
                        "#version 330 core\nlayout (location = 0) in vec3 position;\nuniform mat4 u_mvp;\nvoid main() { gl_Position = u_mvp * vec4(position, 1.0); }")
        fragment (shader! GL20/GL_FRAGMENT_SHADER
                          "#version 330 core\nuniform vec4 u_color;\nout vec4 color;\nvoid main() { color = u_color; }")]
    (GL20/glAttachShader program vertex)
    (GL20/glAttachShader program fragment)
    (GL20/glLinkProgram program)
    (GL20/glDeleteShader vertex)
    (GL20/glDeleteShader fragment)
    (when (= GL11/GL_FALSE (GL20/glGetProgrami program GL20/GL_LINK_STATUS))
      (throw (ex-info "LWJGL shader linking failed" {:log (GL20/glGetProgramInfoLog program)})))
    program))

(defn- texture-program! []
  (let [program (GL20/glCreateProgram)
        vertex (shader! GL20/GL_VERTEX_SHADER
                        "#version 330 core\nlayout (location = 0) in vec3 position;\nlayout (location = 1) in vec2 texcoord;\nuniform mat4 u_mvp;\nout vec2 uv;\nvoid main() { gl_Position = u_mvp * vec4(position, 1.0); uv = texcoord; }")
        fragment (shader! GL20/GL_FRAGMENT_SHADER
                          "#version 330 core\nin vec2 uv;\nuniform sampler2D u_texture;\nuniform vec4 u_tint;\nout vec4 color;\nvoid main() { color = texture(u_texture, uv) * u_tint; }")]
    (GL20/glAttachShader program vertex)
    (GL20/glAttachShader program fragment)
    (GL20/glLinkProgram program)
    (GL20/glDeleteShader vertex)
    (GL20/glDeleteShader fragment)
    (when (= GL11/GL_FALSE (GL20/glGetProgrami program GL20/GL_LINK_STATUS))
      (throw (ex-info "LWJGL texture shader linking failed" {:log (GL20/glGetProgramInfoLog program)})))
    program))

(defn- load-texture! [path]
  (with-open [stack (MemoryStack/stackPush)]
    (let [width (.mallocInt stack 1)
          height (.mallocInt stack 1)
          channels (.mallocInt stack 1)
          pixels (STBImage/stbi_load path width height channels 4)
          texture (GL11/glGenTextures)]
      (when (nil? pixels)
        (throw (ex-info "Unable to load LWJGL image" {:path path :reason (STBImage/stbi_failure_reason)})))
      (try
        (GL11/glBindTexture GL11/GL_TEXTURE_2D texture)
        (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
        (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
        (GL11/glTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGBA (.get width 0) (.get height 0)
                            0 GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE pixels)
        texture
        (finally
          (STBImage/stbi_image_free pixels))))))

(defn- load-assets! [assets]
  (reduce-kv (fn [loaded asset path] (assoc loaded asset (load-texture! path))) {} assets))

(defn- draw-textured-quad! [program texture mvp width height tint]
  (let [vertices (float-array [(- (/ width 2.0)) (- (/ height 2.0)) 0.0 0.0 0.0
                               (/ width 2.0) (- (/ height 2.0)) 0.0 1.0 0.0
                               (/ width 2.0) (/ height 2.0) 0.0 1.0 1.0
                               (- (/ width 2.0)) (- (/ height 2.0)) 0.0 0.0 0.0
                               (/ width 2.0) (/ height 2.0) 0.0 1.0 1.0
                               (- (/ width 2.0)) (/ height 2.0) 0.0 0.0 1.0])
        vao (GL30/glGenVertexArrays)
        vbo (GL15/glGenBuffers)
        buffer (MemoryUtil/memAllocFloat (alength vertices))]
    (try
      (.put buffer vertices)
      (.flip buffer)
      (GL30/glBindVertexArray vao)
      (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo)
      (GL15/glBufferData GL15/GL_ARRAY_BUFFER buffer GL15/GL_STATIC_DRAW)
      (GL20/glVertexAttribPointer 0 3 GL11/GL_FLOAT false 20 0)
      (GL20/glVertexAttribPointer 1 2 GL11/GL_FLOAT false 20 12)
      (GL20/glEnableVertexAttribArray 0)
      (GL20/glEnableVertexAttribArray 1)
      (GL20/glUseProgram program)
      (GL20/glUniformMatrix4fv (GL20/glGetUniformLocation program "u_mvp") false mvp)
      (GL20/glUniform4f (GL20/glGetUniformLocation program "u_tint")
                        (float (nth tint 0)) (float (nth tint 1)) (float (nth tint 2)) 1.0)
      (GL11/glBindTexture GL11/GL_TEXTURE_2D texture)
      (GL11/glDrawArrays GL11/GL_TRIANGLES 0 6)
      (finally
        (MemoryUtil/memFree buffer)
        (GL15/glDeleteBuffers vbo)
        (GL30/glDeleteVertexArrays vao)))))

(defn- draw-vertices! [program vertices primitive mvp rgba]
  (let [vao (GL30/glGenVertexArrays)
        vbo (GL15/glGenBuffers)
        buffer (MemoryUtil/memAllocFloat (alength vertices))]
    (try
      (.put buffer vertices)
      (.flip buffer)
      (GL30/glBindVertexArray vao)
      (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo)
      (GL15/glBufferData GL15/GL_ARRAY_BUFFER buffer GL15/GL_STATIC_DRAW)
      (GL20/glVertexAttribPointer 0 3 GL11/GL_FLOAT false 0 0)
      (GL20/glEnableVertexAttribArray 0)
      (GL20/glUseProgram program)
      (GL20/glUniformMatrix4fv (GL20/glGetUniformLocation program "u_mvp") false mvp)
      (GL20/glUniform4f (GL20/glGetUniformLocation program "u_color")
                        (float (nth rgba 0)) (float (nth rgba 1)) (float (nth rgba 2)) 1.0)
      (GL11/glDrawArrays primitive 0 (/ (alength vertices) 3))
      (finally
        (MemoryUtil/memFree buffer)
        (GL15/glDeleteBuffers vbo)
        (GL30/glDeleteVertexArrays vao)))))

(defn- rectangle-vertices [width height]
  (float-array [(- (/ width 2.0)) (- (/ height 2.0)) 0.0 (/ width 2.0) (- (/ height 2.0)) 0.0
                (/ width 2.0) (/ height 2.0) 0.0 (- (/ width 2.0)) (- (/ height 2.0)) 0.0
                (/ width 2.0) (/ height 2.0) 0.0 (- (/ width 2.0)) (/ height 2.0) 0.0]))

(defn- circle-vertices [radius]
  (float-array
    (mapcat (fn [index]
              (let [first-angle (* 2.0 Math/PI (/ index 32.0))
                    second-angle (* 2.0 Math/PI (/ (inc index) 32.0))]
                [0.0 0.0 0.0 (* radius (Math/cos first-angle)) (* radius (Math/sin first-angle)) 0.0
                 (* radius (Math/cos second-angle)) (* radius (Math/sin second-angle)) 0.0]))
            (range 32))))

(defn- model-matrix [{:keys [position position-3d scale scale-3d rotation-3d]}]
  (let [position (or position-3d position)
        scale (or scale-3d scale)
        translation (translation-matrix [(get position c2d/x (get position c3d/x 0.0))
                                         (get position c2d/y (get position c3d/y 0.0))
                                         (get position c3d/z -5.0)])
        rotation (if rotation-3d (quaternion-matrix rotation-3d) (quaternion-matrix [0.0 0.0 0.0 1.0]))
        scaling (scale-matrix [(get scale c2d/x (get scale c3d/x 1.0))
                               (get scale c2d/y (get scale c3d/y 1.0))
                               (get scale c3d/z 1.0)])]
    (matrix-multiply translation (matrix-multiply rotation scaling))))

(defn- render-shape! [program projection shape model]
  (let [{:keys [kind radius width height fill]} shape
        vertices (case kind
                   :circle (circle-vertices radius)
                   :rectangle (rectangle-vertices width height)
                   (throw (ex-info "Unsupported LWJGL shape kind" {:kind kind})))]
    (draw-vertices! program vertices GL11/GL_TRIANGLES (matrix-multiply projection model)
                    (color (or fill [255 255 255])))))

(defn- render-cube! [program projection cube model]
  (draw-vertices! program (cube-vertices (:size cube)) GL11/GL_TRIANGLES
                  (matrix-multiply projection model) (color (or (:color cube) [255 255 255]))))

(defn- render-sprite! [program textures projection sprite model]
  (let [texture (get textures (:asset sprite))]
    (when-not texture
      (throw (ex-info "No loaded LWJGL asset matches the sprite key" {:asset (:asset sprite)})))
    (draw-textured-quad! program texture (matrix-multiply projection model)
                          (or (:width sprite) 1.0) (or (:height sprite) 1.0)
                          (color (or (:tint sprite) [255 255 255])))))

(defn- key-pressed? [window key]
  (= GLFW/GLFW_PRESS (GLFW/glfwGetKey window key)))

(defn- poll-keys [window]
  (into #{}
        (keep (fn [[key code]] (when (key-pressed? window code) key)))
        {:left GLFW/GLFW_KEY_LEFT :right GLFW/GLFW_KEY_RIGHT :up GLFW/GLFW_KEY_UP
         :down GLFW/GLFW_KEY_DOWN :a GLFW/GLFW_KEY_A :d GLFW/GLFW_KEY_D :w GLFW/GLFW_KEY_W
         :s GLFW/GLFW_KEY_S :space GLFW/GLFW_KEY_SPACE :r GLFW/GLFW_KEY_R
         :escape GLFW/GLFW_KEY_ESCAPE}))

(defn- initialize-window! [{:keys [width height title]}]
  (when-not (GLFW/glfwInit) (throw (ex-info "Unable to initialize GLFW" {})))
  (GLFW/glfwDefaultWindowHints)
  (GLFW/glfwWindowHint GLFW/GLFW_CONTEXT_VERSION_MAJOR 3)
  (GLFW/glfwWindowHint GLFW/GLFW_CONTEXT_VERSION_MINOR 3)
  (GLFW/glfwWindowHint GLFW/GLFW_OPENGL_PROFILE GLFW/GLFW_OPENGL_CORE_PROFILE)
  (GLFW/glfwWindowHint GLFW/GLFW_OPENGL_FORWARD_COMPAT GLFW/GLFW_TRUE)
  (let [window (GLFW/glfwCreateWindow width height title MemoryUtil/NULL MemoryUtil/NULL)]
    (when (zero? window)
      (GLFW/glfwTerminate)
      (throw (ex-info "Unable to create LWJGL window" {:width width :height height})))
    (GLFW/glfwMakeContextCurrent window)
    (GLFW/glfwSwapInterval 1)
    (GLFW/glfwShowWindow window)
    (GL/createCapabilities)
    window))

(defn- render-world! [program texture-program textures world width height background]
  (apply GL11/glClearColor (map float (conj (color background) 1.0)))
  (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))
  (GL11/glEnable GL11/GL_DEPTH_TEST)
  (let [projection-2d (orthographic-matrix width height)
        camera (camera-for-world world)
        projection-3d (matrix-multiply
                        (perspective-matrix width height (:fov camera) (:near camera) (:far camera))
                        (view-matrix camera))
        drawables (drawable-entities world)]
    (doseq [{:keys [cube] :as drawable} (filter :cube drawables)]
      (render-cube! program projection-3d cube (model-matrix drawable)))
    (GL11/glClear GL11/GL_DEPTH_BUFFER_BIT)
    (GL11/glDisable GL11/GL_DEPTH_TEST)
    (doseq [{:keys [shape sprite] :as drawable} (filter #(or (:shape %) (:sprite %)) drawables)]
      (let [model (model-matrix drawable)]
        (if shape
          (render-shape! program projection-2d shape model)
          (render-sprite! texture-program textures projection-2d sprite model)))))
  )

(defn start!
  [{:keys [runtime world width height title background assets frame-rate command-fn event-fn overlay-fn]
    :or {width 800 height 600 title "Morphe" background [0 0 0] assets {} frame-rate 60.0
         command-fn (fn [_world _pressed-keys] [])}}]
  (validate-options {:command-fn command-fn :event-fn event-fn :overlay-fn overlay-fn})
  (let [window (initialize-window! {:width width :height height :title title})
        program (program!)
        texture-program (texture-program!)
        textures (load-assets! assets)]
    (try
      (loop [current-world world last-time (GLFW/glfwGetTime)]
        (when-not (or (GLFW/glfwWindowShouldClose window)
                      (contains? (poll-keys window) :escape))
          (GLFW/glfwPollEvents)
          (let [pressed-keys (poll-keys window)
                current-time (GLFW/glfwGetTime)
                dt (min 0.25 (if (pos? last-time) (- current-time last-time) (/ 1.0 frame-rate)))
                result (ecs/step runtime current-world dt (command-fn current-world pressed-keys))]
            (render-world! program texture-program textures (:world result) width height background)
            (when overlay-fn (overlay-fn (:world result) pressed-keys (:events result)))
            (GLFW/glfwSwapBuffers window)
            (when event-fn (event-fn (:events result)))
            (recur (:world result) current-time))))
      (finally
        (GL20/glDeleteProgram program)
        (GL20/glDeleteProgram texture-program)
        (doseq [texture (vals textures)] (GL11/glDeleteTextures texture))
        (GLFW/glfwDestroyWindow window)
        (GLFW/glfwTerminate)))))
