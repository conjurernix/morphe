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

Run this module's tests with `clojure -M:test`.

## Benchmark

Run `clojure -M:benchmark --quick` for a short comparison or `clojure -M:benchmark` for the 60-sample result. The harness compares the shipped data-oriented runtime with the preserved baseline.
