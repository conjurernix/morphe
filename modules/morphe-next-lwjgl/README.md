# Morphe LWJGL adapter

`morphe.adapters.lwjgl` runs a Morphe game loop with GLFW input, buffered OpenGL 2D rendering, packaged assets, OpenAL audio, and effect handlers.

On Apple Silicon, include the platform native alias when running directly from this module:

```sh
clojure -M:macos-arm64
```

Other module aliases are `:macos-x64`, `:linux-x64`, and `:windows-x64`. Applications include the matching LWJGL natives when they package a game.

```clojure
(require '[morphe.adapters.lwjgl :as lwjgl])

(lwjgl/start!
  {:game-state initial-game
   :sources [tick-source]
   :update-hz 60
   :input-fn input-events
   :app-event-fn handle-application-event
   :error-fn handle-fatal-error
   :effect-handlers {:sound/play play-sound!}})
```

Render handlers return `:rectangle`, `:circle`, `:line`, `:polygon`, `:sprite`, or `:text` descriptors. Sprite assets map keys to image paths.

## Image assets

Use `morphe.adapters.lwjgl.assets/image` for classpath resources or filesystem paths:

```clojure
(require '[morphe.adapters.lwjgl.assets :as assets])

{:assets {:player (assets/image "sprites/player.png"
                                :filter :nearest
                                :wrap :clamp)}}
```

Classpath lookup runs before filesystem lookup, so the same descriptor works from source and a packaged JAR. Duplicate descriptors upload once. Shutdown releases every distinct texture.

Strings remain accepted as linear, clamped image descriptors.

## Fonts and text

Use `morphe.adapters.lwjgl.assets/font` to bake printable ASCII from a TrueType font into a texture atlas. The requested size is the font's line height in pixels.

```clojure
(require '[morphe.adapters.lwjgl.assets :as assets])

{:assets {:ui (assets/font "fonts/game.ttf" 24)}}
```

Render text from its baseline with `lwjgl/text`. Newlines advance by one line height. The font asset must include every requested character.

```clojure
(lwjgl/text :ui "Score: 42" 24 48 [255 255 255])
```

## Buffered renderer

Rectangles, circles, and sprites compile into triangles and adjacent texture batches. One VBO and native staging buffer are reused across frames.

Sprites use their image dimensions by default. `:region [x y width height]` selects a pixel region from an atlas.

```clojure
(lwjgl/sprite :tiles 160 96
              :region [32 0 16 16]
              :width 32
              :height 32)
```

Layer order remains stable. Batching never reorders blended render data.

## Transforms and cameras

Every render descriptor may include `:transform`. It applies scale, rotation in radians, and translation around an optional origin before rendering.

```clojure
{:kind :rectangle
 :x 40 :y 40 :width 32 :height 16 :fill [255 255 255]
 :transform {:origin [40 40]
             :rotation 0.5
             :scale [1.5 1.5]
             :translate [12 0]}}
```

Set `:morphe.render/camera` in `:render-context` to map a world position to the viewport's top-left corner. `:zoom` defaults to `1.0`.

```clojure
{:render-context {:morphe.render/camera (lwjgl/camera 160 90 :zoom 1.5)}}
```

Lines use `:from`, `:to`, `:width`, and `:fill`. Polygons use `:points` as `[x y]` pairs and `:fill`; they support concave, non-self-intersecting shapes.

## Clipping

Attach a viewport-space clip rectangle to any render descriptor. Coordinates use the viewport's top-left origin and integer pixels. Commands with different clips remain separate batches.

```clojure
(assoc (lwjgl/text :ui "Paused" 24 48 [255 255 255])
       :clip (lwjgl/clip-rect 16 16 240 120))
```

## Canvases

Create an off-screen canvas in `:assets`. Add `:target` to commands that draw into it, then draw its texture with `lwjgl/sprite`. Canvas contents clear to transparent before their first command each frame.

```clojure
{:assets {:scene (assets/canvas 320 180)}}

[{:kind :rectangle :x 160 :y 90 :width 320 :height 180
   :fill [17 23 42] :target :scene}
 (lwjgl/sprite :scene 400 300 :width 640 :height 360)]
```

Render target commands must precede the sprite that reads their canvas. Clip rectangles use the target canvas dimensions.

## Fixed updates

`start!` separates rendering from simulation. These options control the scheduler:

| Option | Default | Meaning |
| --- | --- | --- |
| `:update-hz` | `:frame-rate`, normally 60 | Fixed simulation updates per second |
| `:max-catch-up-updates` | `5` | Most updates allowed in one rendered frame |
| `:max-frame-seconds` | `0.25` | Most wall-clock time accepted from one frame |
| `:render-context` | `{}` | Base data passed to render handlers |

The adapter adds `:morphe.render/alpha` and `:morphe.render/previous-game` to the render context. Use `morphe.fixed-step/previous-entity-state` and `interpolate-number` for smooth motion.

## Input and application control

`:input-fn` receives game state, held keys, and key presses. It may return a sequence of mapped Morphe events.

Use `:input-state-fn` to receive the complete frame:

```clojure
(fn [game-state input-frame]
  ;; {:keys #{:w}
  ;;  :mouse-buttons #{:left}
  ;;  :cursor {:x 120.0 :y 80.0}
  ;;  :events [...]
  ;;  :gamepads {0 {...}}
  ;;  :window {:fullscreen? false :vsync? true :clipboard "..."}}
  {:events (map-input input-frame)
   :controls []})
```

Event types include `:key/pressed`, `:key/released`, `:key/repeated`, `:text/input`, `:mouse/moved`, `:mouse/pressed`, `:mouse/released`, `:mouse/scrolled`, and joystick or gamepad connection changes.

Standard gamepad snapshots contain named axes and pressed buttons. Key events retain GLFW key codes, scancodes, and modifier sets for remapping.

Return `{:events [...] :controls [...]}` when input also controls the application. Controls are one-item vectors containing `:app/quit`, `:app/cancel-quit`, `:app/pause`, or `:app/resume`.

`:app-event-fn` receives application data, game state, and a close, focus, framebuffer-resize, or content-scale event. It returns this shape:

```clojure
{:app next-app-state
 :events mapped-game-events
 :controls [[:app/pause]]}
```

Close requests quit by default. Focus changes update application state but do not pause unless the callback requests it. Resize events update the viewport before rendering. Content-scale events use `:morphe.app/content-scale-changed`. They update application `:content-scale` to `{:x ... :y ...}`.

Paused games continue polling and rendering. They discard elapsed simulation time and gameplay input. Application events still run.

`:error-fn` receives the throwable and phase data after a fatal loop error. Return `:close` to exit cleanly or `:rethrow` to propagate it. The default is `:rethrow`; native cleanup runs in both cases.

`:event-fn` receives each completed frame result. Its `:ticks` value can be appended to a `morphe.replay` recording.

## Windows and displays

Pass `:window` to configure startup behavior. All options default to the values below.

```clojure
{:window {:fullscreen? false
          :vsync? true
          :resizable? true}}
```

Emit `[:window/fullscreen true]`, `[:window/vsync false]`, or `[:window/clipboard-set "Morphe"]` to control the window or copy text. `:input-state-fn` receives fullscreen state, VSync state, and clipboard text under `:window`.

Use `morphe.adapters.lwjgl.window/display-info` after `start!` begins to read display names, positions, physical sizes, content scales, and video modes as plain data.

## Audio

`morphe.adapters.lwjgl.audio` loads static Ogg Vorbis sounds into OpenAL and streams music through reusable buffers:

```clojure
(require '[morphe.adapters.lwjgl.audio :as audio])

(lwjgl/start!
  {:game-state initial-game
   :audio {:sounds {:jump (audio/sound "sounds/jump.ogg")}
           :music {:theme (audio/music "music/theme.ogg")}
           :groups {:music 0.7 :effects 0.9}
           :volume 0.8
           :max-voices 32}})
```

Emit `[:audio/play :jump]` or `[:audio/play :jump {:group :effects :volume 0.5 :pitch 1.2 :loop? false}]`. Start streamed music with `[:audio/music-play :theme]`. Its options are `:group`, `:volume`, `:loop?`, and `:seek-seconds`.

Music loops by default. Use `[:audio/music-play :theme {:loop? true :group :music}]`, `[:audio/music-seek 30.0]`, or `[:audio/music-stop]` to control the active stream. Use `[:audio/group-volume :music 0.6]` to adjust every active sound and music stream in a group. The `:master` group always exists and starts at gain `1.0`.

Built-in effects also support `[:audio/stop]` and `[:audio/volume 0.7]`.

Application pause and resume controls pause and resume active sources. Shutdown releases sources, buffers, the OpenAL context, and its device.

The voice limit defaults to 32. Playback reclaims stopped sources and replaces the oldest active source at the limit.
