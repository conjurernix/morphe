(ns morphe.lwjgl-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.adapters.lwjgl :as lwjgl]
            [morphe.adapters.lwjgl.assets :as assets]
            [morphe.adapters.lwjgl.audio :as audio]
            [morphe.adapters.lwjgl.input :as input]
            [morphe.adapters.lwjgl.renderer :as renderer]
            [morphe.adapters.lwjgl.window :as window]))

(deftest keyboard-state-is-immutable
  (let [initial {:pressed-keys #{}}
        pressed (lwjgl/press-key initial :w)
        released (lwjgl/release-key pressed :w)]
    (is (= #{} (:pressed-keys initial)))
    (is (= #{:w} (:pressed-keys pressed)))
    (is (= #{} (:pressed-keys released)))
    (is (= [:w] (:key-events pressed)))))

(deftest render-descriptors-are-data
  (testing "shapes"
    (is (= {:kind :rectangle :x 10 :y 20 :width 30 :height 40 :fill [1 2 3]}
           (lwjgl/rectangle 10 20 30 40 [1 2 3])))
    (is (= {:kind :circle :x 10 :y 20 :radius 5 :fill [1 2 3]}
           (lwjgl/circle 10 20 5 [1 2 3])))
    (is (= {:kind :line :from [0 0] :to [10 0] :width 2 :fill [1 2 3]}
           (lwjgl/line [0 0] [10 0] 2 [1 2 3])))
    (is (= {:kind :polygon :points [[0 0] [10 0] [0 10]] :fill [1 2 3]}
           (lwjgl/polygon [[0 0] [10 0] [0 10]] [1 2 3]))))
  (testing "sprites"
    (is (= {:kind :sprite :asset :player :x 10 :y 20}
           (lwjgl/sprite :player 10 20)))
    (is (= {:kind :sprite :asset :player :x 10 :y 20
                         :width 32 :height 48 :tint [255 0 0]}
           (lwjgl/sprite :player 10 20
                         :width 32 :height 48 :tint [255 0 0]))))
  (is (= {:x 10 :y 20 :zoom 1.5}
         (lwjgl/camera 10 20 :zoom 1.5))))

(deftest asset-resolution-is-explicit
  (let [image (Object.)]
    (is (identical? image (lwjgl/resolve-asset {:player image} :player)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lwjgl/resolve-asset {} :missing)))))

(deftest input-results-support-events-and-application-controls
  (let [event {:target nil :message [:input/run]}]
    (is (= {:events [event] :controls []}
           (lwjgl/normalize-input-result [event])))
    (is (= {:events [event] :controls [[:app/pause]]}
           (lwjgl/normalize-input-result
             {:events [event] :controls [[:app/pause]]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lwjgl/normalize-input-result :invalid)))))

(deftest packaged-asset-descriptors-are-explicit
  (is (= {:morphe.asset/type :image
          :path "sprites/player.png"
          :filter :nearest
          :wrap :repeat}
         (assets/image "sprites/player.png"
                       :filter :nearest :wrap :repeat)))
  (is (= {:morphe.asset/type :font
          :path "fonts/game.ttf"
          :size 18
          :atlas-size 256
          :filter :linear}
         (assets/font "fonts/game.ttf" 18 :atlas-size 256)))
  (is (= {:morphe.asset/type :canvas
          :width 320
          :height 180
          :filter :nearest}
         (assets/canvas 320 180 :filter :nearest)))
  (is (pos? (alength (assets/read-bytes! "morphe/lwjgl_test.clj"))))
  (is (thrown? clojure.lang.ExceptionInfo
               (assets/read-bytes! "missing/asset.png"))))

(deftest buffered-render-data-batches-adjacent-textures
  (let [loaded {:player {:morphe.asset/type :image
                         :texture-id 7 :width 32 :height 16}}
        batches (renderer/compile-batches
                  loaded
                  [(lwjgl/rectangle 10 10 5 5 [255 0 0])
                   (lwjgl/rectangle 20 10 5 5 [0 255 0])
                   (lwjgl/sprite :player 10 10 :region [0 0 16 16])
                   (lwjgl/sprite :player 30 10 :region [16 0 16 16])])]
    (is (= [0 7] (mapv :texture-id batches)))
    (is (= [12 12] (mapv #(count (:vertices %)) batches)))
    (is (= 8 (count (-> batches first :vertices first))))))

(deftest transformed-render-data-preserves-triangle-batching
  (let [batches (renderer/compile-batches
                 {}
                 [{:kind :rectangle :x 10 :y 10 :width 4 :height 2
                   :fill [255 0 0]
                   :transform {:translate [2 3] :scale [2 1]}}]
                 (lwjgl/camera 10 2 :zoom 0.5))]
    (is (= 1 (count batches)))
    (is (= [4.0 5.0]
           (subvec (-> batches first :vertices first) 0 2)))))

(deftest line-and-concave-polygon-compile-to-triangles
  (let [batches (renderer/compile-batches
                 {}
                 [(lwjgl/line [0 0] [10 0] 2 [255 255 255])
                  (lwjgl/polygon [[0 0] [10 0] [10 10] [5 5] [0 10]]
                                  [255 0 0])])]
    (is (= [0] (mapv :texture-id batches)))
    (is (= 15 (count (-> batches first :vertices))))))

(deftest clipping-preserves-batch-boundaries
  (let [clip (lwjgl/clip-rect 10 20 80 60)
        batches (renderer/compile-batches
                 {}
                 [(assoc (lwjgl/rectangle 20 20 10 10 [255 0 0]) :clip clip)
                  (assoc (lwjgl/rectangle 40 20 10 10 [0 255 0]) :clip clip)
                  (assoc (lwjgl/rectangle 60 20 10 10 [0 0 255])
                         :clip (lwjgl/clip-rect 0 0 20 20))])]
    (is (= [{:x 10 :y 20 :width 80 :height 60}
            {:x 0 :y 0 :width 20 :height 20}]
           (mapv :clip batches)))
    (is (= [12 6] (mapv #(count (:vertices %)) batches)))))

(deftest canvas-targets-remain-separated-from-screen-batches
  (let [canvas {:morphe.asset/type :canvas
                :framebuffer-id 8
                :texture-id 7
                :width 320
                :height 180}
        batches (renderer/compile-batches
                 {:scene canvas}
                 [(assoc (lwjgl/rectangle 20 20 10 10 [255 0 0]) :target :scene)
                  (lwjgl/sprite :scene 160 90)])]
    (is (= [:scene nil] (mapv :target batches)))
    (is (= [0 7] (mapv :texture-id batches)))))

(deftest text-compiles-from-font-glyph-data
  (let [font {:morphe.asset/type :font
              :texture-id 11
              :width 64
              :height 64
              :line-height 12
              :glyphs {\A {:x0 0 :y0 0 :x1 6 :y1 8
                            :xoff 1.0 :yoff -7.0 :xadvance 7.0}}}
        batches (renderer/compile-batches
                 {:ui font}
                 [(lwjgl/text :ui "A\nA" 10 20 [255 255 255])])]
    (is (= [11] (mapv :texture-id batches)))
    (is (= 12 (count (-> batches first :vertices))))
    (is (= [11.0 13.0]
           (subvec (-> batches first :vertices first) 0 2)))
    (is (= [11.0 25.0]
           (subvec (-> batches first :vertices (nth 6)) 0 2)))))

(deftest input-data-covers-key-names-and-modifiers
  (is (= :a (input/key-name 65)))
  (is (= :f25 (input/key-name 314)))
  (is (= (keyword "key" "999") (input/key-name 999)))
  (is (= #{:shift :alt :caps-lock} (input/modifiers 21))))

(deftest window-options-have-stable-defaults
  (is (= {:fullscreen? false :vsync? true :resizable? true}
         (window/options {})))
  (is (= {:fullscreen? true :vsync? false :resizable? false}
         (window/options {:fullscreen? true :vsync? false :resizable? false})))
  (is (thrown? clojure.lang.ExceptionInfo (window/options :fullscreen)))
  (is (thrown? clojure.lang.ExceptionInfo (window/options {:title "Morphe"})))
  (is (thrown? clojure.lang.ExceptionInfo (window/options {:vsync? :yes}))))

(deftest audio-assets-have-a-stable-shape
  (is (= {:morphe.audio/type :sound :path "sounds/jump.ogg"}
         (audio/sound "sounds/jump.ogg")))
  (is (= {:morphe.audio/type :music :path "music/theme.ogg"}
         (audio/music "music/theme.ogg")))
  (is (thrown? clojure.lang.ExceptionInfo (audio/sound "")))
  (is (thrown? clojure.lang.ExceptionInfo (audio/music nil))))
