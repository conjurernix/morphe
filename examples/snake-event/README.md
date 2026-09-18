# Event-driven Snake

Snake demonstrates entity-local state, component ownership, targeted events, pure render data, and effects.

Start the game from this directory:

```sh
clojure -M:run
```

Run its tests:

```sh
clojure -M:test
```

`snake_event/snake.clj` owns movement and score behavior. `snake_event/food.clj` owns food behavior. `snake_event/loop.clj` connects the pure game to Quil.
