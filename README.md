# Morphe

Morphe is a functional, engine-agnostic Entity Component System for Clojure. A world is immutable data, systems are
ordered transformations, and each step returns the next world together with transient events.

For an agent-oriented project map, see [llms.txt](llms.txt). The expanded reference is
in [llms-full.txt](llms-full.txt).

```clojure
(require '[morphe.components.2d :as c2d]
         '[morphe.core :as ecs]
         '[morphe.query :as q]
         '[morphe.runtime :as runtime]
         '[morphe.system :as system])

(def movement
  (system/entity-system
    {:name :game/movement :query (q/all c2d/position-key c2d/velocity-key)}
    (fn [world entity dt]
      (let [position (ecs/component world entity c2d/position-key)
            velocity (ecs/component world entity c2d/velocity-key)]
        (ecs/set-component
          world entity c2d/position-key
          {c2d/x (+ (get position c2d/x) (* (get velocity c2d/x) dt))
           c2d/y (+ (get position c2d/y) (* (get velocity c2d/y) dt))})))))

(let [[world player] (ecs/spawn (ecs/world)
                                {c2d/position-key (c2d/position 0.0 0.0)
                                 c2d/velocity-key (c2d/velocity 10.0 0.0)})]
  (ecs/step (runtime/runtime [movement]) world 0.016))
```

Systems are protocol-backed values. Use `system/entity-system` for per-entity behavior and `system/system` with an
`:operation` function for world-level behavior. Custom system implementations can satisfy
`morphe.system/ExecutableSystem` directly.

Events use namespaced keyword types and remain transient step output:

```clojure
(ecs/event :game/entity-defeated {:entity enemy})
;; => {:type :game/entity-defeated :entity enemy}
```

Commands enter explicitly at the start of a step and are handled by dedicated command systems:

```clojure
(def runtime
  (runtime/runtime {:command-systems [apply-input]
                    :systems         [movement]}))

(ecs/step runtime world dt
          [(ecs/command :game/move-forward {:entity player})])
```

Components and fields are namespaced keywords owned by their component namespace. Rendering, physics, input, audio, and
game loops belong in adapters.

The optional Quil adapter provides a window loop, shape and sprite rendering, and held-key tracking. Add render-owned
components to entities and keep image paths in the adapter configuration:

```clojure
(require '[morphe.adapters.quil :as quil])

(quil/start!
  {:runtime    game-runtime
   :world      initial-world
   :assets     {:player "assets/player.png"}
   :command-fn (fn [_world pressed-keys]
                 (if (contains? pressed-keys :w)
                   [(ecs/command :game/move-forward {:entity player})]
                   []))})
```

Run the Quil module tests from its module directory:

```sh
cd modules/quil
clojure -M:test
```

The optional LWJGL adapter provides GLFW windowing, held-key polling, OpenGL 3.3 rendering for 2D shapes and sprites,
and basic 3D colored cubes. Run its tests from `modules/lwjgl` with `clojure -M:test` plus the platform alias:
`-M:macos-arm64`, `-M:linux`, or `-M:windows`. The runnable example is in `examples/lwjgl`; start it with
`clojure -M:run` from that directory.

The repository root is a workspace coordinator. Core, Quil, and LWJGL each own their source paths, dependencies, and
tests under `modules/`.

The root `build.clj` provides packaging and release tasks. Run `clojure -T:build ci :version '"0.1.0-test"'` for the
full local pipeline, `clojure -T:build jar` to build all module JARs, and `clojure -T:build deploy` to publish built
artifacts to Clojars with configured credentials.
