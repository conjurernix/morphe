# Morphe

Morphe is a purely functional game state engine for Clojure. Entities own local state, components add reusable behavior, and events drive every transition.

## Install

Add the core engine to `deps.edn`:

```clojure
{:deps {io.github.nikolaspafitis/morphe.core {:mvn/version "0.2.0"}}}
```

Add the optional Quil adapter for a window, input, and 2D rendering:

```clojure
{:deps {io.github.nikolaspafitis/morphe.quil {:mvn/version "0.2.0"}}}
```

Add the optional LWJGL adapter for GLFW input and OpenGL 2D rendering:

```clojure
{:deps {io.github.nikolaspafitis/morphe.lwjgl {:mvn/version "0.2.0"}}}
```

## Quickstart

```clojure
(require '[morphe.core :as game])

(def player
  (game/entity
    {:x 0}
    []
    (fn [state _context [_ amount]]
      [(update state :x + amount) []])
    (fn [{:keys [x]} _context _render-context]
      [{:kind :circle :x x :y 20 :radius 10
        :fill [44 117 255]}])))

(def initial-game
  (game/add-entity (game/game) :player player))

(game/step initial-game [] 0.016
           [(game/event :player [:player/move 10])])
```

`step` returns `{:game next-game :effects effects}`. Keep the returned game for the next frame and interpret effects at an adapter boundary.

Run the complete Snake example:

```sh
cd examples/snake-event
clojure -M:run
```

## Modules

| Artifact | Namespace | Purpose |
| --- | --- | --- |
| `morphe.core` | `morphe.core`, `morphe.fixed-step`, `morphe.application`, `morphe.replay`, `morphe.random`, `morphe.collision` | State, deterministic updates, application control, replay, random state, and collision queries |
| `morphe.quil` | `morphe.adapters.quil` | Quil loop, keyboard input, assets, 2D rendering, and effects |
| `morphe.lwjgl` | `morphe.adapters.lwjgl`, `.assets`, `.audio`, `.input`, `.renderer` | Buffered desktop rendering, packaged assets, input, OpenAL audio, and application control |

## Core model

A game contains a context map, entities by ID, and explicit entity order. Treat the returned game as immutable data and use the public functions to update it.

Entity handlers receive local state, game context, and a message. Component handlers receive the same values.

```clojure
[new-state outputs]
```

Messages and effects are vectors beginning with a keyword. Namespaced keywords make ownership clear.

```clojure
[:player/move 10]
[:sound/play :coin]
```

Use `send`, `broadcast`, and `effect` to create outputs. Generated events join the same FIFO queue as external events.

```clojure
[(game/send :enemy [:enemy/hit 5])
 (game/broadcast [:round/finished])
 (game/effect [:sound/play :hit])]
```

Use lifecycle outputs when an event must change the game structure. Commands apply after every recipient handles the current event and before the next queued event.

```clojure
[(game/spawn :enemy-2 enemy)
 (game/despawn :enemy-1)
 (game/assoc-context :wave 2)]
```

`remove-entity`, `replace-entity`, `update-context`, and `replace-scene` provide the same immutable operations outside handlers.

External events run before source events in each step. Broadcasts and rendering follow entity insertion order.

The runtime stops after 10,000 events by default. Set a positive `:max-events` through the five-argument `step` form when a game needs another bound.

```clojure
(game/step state sources dt events {:max-events 20000})
```

## Components

Each component has a unique, namespace-qualified key. It may read the entity state but may change only its owned top-level key.

```clojure
(def score-key ::score)

(def score-component
  (game/component
    score-key
    (fn [state _context message]
      (if (= :score/add (first message))
        [(update state score-key inc) []]
        [state []]))))
```

Entity handlers cannot change component-owned keys. Both handler types receive each message, with the entity handler running first.

## Snapshots

Create saved state with typed entities. Restore it through factories that rebuild behavior from saved entity state.

```clojure
(defn create-player [state]
  (game/typed-entity :player state [] update-player render-player))

(def saved (game/snapshot game-state))
(def loaded (game/restore saved {:player create-player}))
```

Snapshots contain context, entity IDs, types, states, and insertion order. Games remain responsible for writing that data to storage.

## Fixed updates and replay

`morphe.fixed-step` runs simulation at a configured rate. It bounds catch-up work and reports interpolation data for rendering.

```clojure
(require '[morphe.fixed-step :as fixed-step])

(def loop-state
  (fixed-step/create game-state
                     {:update-hz 60
                      :max-catch-up-updates 5
                      :max-frame-seconds 0.25}))

(fixed-step/advance loop-state sources elapsed-seconds mapped-events)
```

Each result contains the next `:loop`, ordered `:effects`, `:updates`, render `:alpha`, `:dropped-seconds`, and replay `:ticks`.

Use `morphe.replay` to record those tick entries. Playback restores the initial snapshot and verifies the final snapshot.

Use `morphe.random` for deterministic random values. Each draw returns `[next-random value]`; keep the state inside a snapshot.

## Failure behavior

Morphe rejects malformed state, events, effects, handler results, and source results at their public boundary. A targeted event for an absent entity throws with the entity ID and event in `ex-data`.

Handler failures include the entity ID, event, and component key when available. Event cycles fail with `:phase :event-drain` and the processed count.

## Quil adapter

```clojure
(require '[morphe.adapters.quil :as quil])

(quil/start!
  {:game-state initial-game
   :sources [tick-source]
   :input-fn input-events
   :effect-handlers {:sound/play play-sound!}})
```

Render handlers return rectangles, circles, sprites, or adapter-specific data. The adapter owns the window, clock, keyboard state, assets, and effects.

## LWJGL application loop

The LWJGL adapter runs fixed updates through a shader and VBO renderer. Image descriptors load classpath or filesystem resources and support atlas regions.

`:input-fn` may return mapped events or `{:events [...] :controls [...]}`. Use `:input-state-fn` for keyboard, text, mouse, wheel, and standard gamepad data.

Use `:app-event-fn` for close, focus, and resize events. It returns updated application data, game events, and controls.

```clojure
(fn [app-state game-state app-event]
  {:app app-state
   :events []
   :controls [[:app/pause]]})
```

Supported controls are `:app/quit`, `:app/cancel-quit`, `:app/pause`, and `:app/resume`. `:error-fn` returns `:rethrow` or `:close` after a fatal error.

Configure `:audio {:sounds {...}}` to enable OpenAL. The adapter handles `:audio/play`, `:audio/stop`, and `:audio/volume` effects and releases audio resources on shutdown.

## Development

Run the full local pipeline:

```sh
clojure -T:build ci :version '"0.2.0-test"'
```

This command lints, tests, builds all three JARs, installs them locally, and requires their public namespaces through Maven coordinates.

See [Design](docs/DESIGN.md), [v0.1 roadmap](docs/V0.1-ROADMAP.md), [0.2 migration](docs/MIGRATION-0.2.md), and [Changelog](CHANGELOG.md) for deeper contracts and release details.
