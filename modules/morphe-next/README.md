# Morphe core

`morphe.core` provides immutable game state, entities, components, FIFO events, pure render data, and effect descriptors.

```clojure
(require '[morphe.core :as game])

(def player
  (game/entity {:x 0} []
               (fn [state _context [_ amount]]
                 [(update state :x + amount) []])))

(def state (game/add-entity (game/game) :player player))

(game/step state [] 0.016
           [(game/event :player [:player/move 10])])
```

Handlers can return `spawn`, `despawn`, `replace-entity-command`, context, and scene commands. Commands apply after the current event delivery.

Use `typed-entity`, `snapshot`, and `restore` when game state must be saved. Restore factories rebuild handlers from each entity's saved state.

`morphe.fixed-step` provides a pure accumulator with configurable update rate, catch-up limit, frame-time clamp, interpolation alpha, and replay tick records.

`morphe.application` reduces quit, focus, and resize events into immutable state. Application callbacks may emit mapped game events and quit, pause, resume, or close-cancellation controls.

`morphe.replay` records mapped events by tick and verifies the final snapshot during playback. `morphe.random` provides snapshot-safe deterministic random state.

`morphe.collision` provides pure center-based AABBs, uniform-grid spatial indexes, overlap queries, and ray casts. Build an index once for stable world geometry, then query it during simulation.

Run this module's Clojure tests with `clojure -M:test`. Run the ClojureScript runtime smoke test with `clojure -M:test-cljs`.

## Benchmark

Run `clojure -M:benchmark --quick` for a short comparison or `clojure -M:benchmark` for the 60-sample result. The harness compares the shipped data-oriented runtime with the preserved baseline.
