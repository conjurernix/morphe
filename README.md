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
| `morphe.core` | `morphe.core` | State, entities, components, events, rendering data, and effects |
| `morphe.quil` | `morphe.adapters.quil` | Quil loop, keyboard input, assets, 2D rendering, and effects |

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

## Development

Run the full local pipeline:

```sh
clojure -T:build ci :version '"0.2.0-test"'
```

This command lints, tests, builds both JARs, installs them locally, and requires their public namespaces through Maven coordinates.

See [Design](docs/DESIGN.md), [0.2 migration](docs/MIGRATION-0.2.md), and [Changelog](CHANGELOG.md) for deeper contracts and release details.
