# Migrating to Morphe 0.2

Morphe 0.2 replaces the ECS API with an event-driven game state engine. It has no compatibility layer.

## Dependency and namespace

Keep the core Maven coordinate and update application code to the focused public namespace:

```clojure
io.github.nikolaspafitis/morphe.core {:mvn/version "0.2.0"}
```

```clojure
(require '[morphe.core :as game])
```

The Quil coordinate remains `io.github.nikolaspafitis/morphe.quil`. Its namespace remains `morphe.adapters.quil`.

## Model changes

| Morphe 0.1 | Morphe 0.2 |
| --- | --- |
| World and generated entity IDs | Game with explicit entity IDs |
| Component maps and queries | Entity-local state and ordered components |
| Ordered systems | Entity and component message handlers |
| Commands before systems | Targeted or broadcast events |
| Transient step events | FIFO generated events and returned effects |
| Renderer-owned ECS components | Pure entity render handlers |

Replace `world`, `spawn`, systems, queries, resources, commands, and component mutation calls. Build entities with `entity`, register them with `add-entity`, then update them through events.

## Component ownership

Give every component a unique, namespace-qualified key. Initialize its state under that key in the entity state map.

Components may read the complete entity state. They may change only their own top-level key.

Move changes to component-owned state out of entity handlers. Handle resets and related events inside the owning component.

## Runtime ordering

Pass external input and event sources separately to `step`. External input runs first, followed by source events.

Generated messages use the same FIFO queue. Broadcast delivery and rendering follow entity insertion order.

Handle missing entity targets as errors. Use the five-argument `step` form to change the 10,000-event safety bound.

## Effects

Return effects with `effect` instead of executing them inside handlers. Register side-effecting functions at the adapter boundary.

```clojure
(game/dispatch-effects!
  {:sound/play play-sound!}
  (:effects step-result))
```

## Removed APIs

The `morphe.command`, `morphe.component`, `morphe.entity`, `morphe.event`, `morphe.query`, `morphe.resource`, `morphe.runtime`, `morphe.system`, and `morphe.world` namespaces are removed.

The LWJGL module and old platformer examples are removed. The event-driven Snake project is the maintained example.
