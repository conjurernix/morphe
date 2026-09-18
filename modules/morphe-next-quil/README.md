# Morphe Quil

`morphe.adapters.quil` runs a Morphe game loop with keyboard input, pure render data, assets, and effect handlers.

```clojure
(require '[morphe.adapters.quil :as quil])

(quil/start!
  {:game-state initial-game
   :sources [tick-source]
   :input-fn input-events
   :effect-handlers {:sound/play play-sound!}})
```

Run this module's tests with `clojure -M:test`.
