# Morphe design

## Purpose

Morphe applies immutable model, update, view, and effect patterns to small games. The core owns deterministic transitions. Adapters own side effects.

## Game state

A game has three fields:

```clojure
{:context {...}
 :entities {entity-id entity}
 :entity-order [entity-id ...]}
```

The order vector makes broadcasts and rendering independent of map iteration. Entity IDs may be any non-nil immutable Clojure value.

## Entities and components

An entity contains a state map, ordered components, an update handler, and an optional render handler.

An update handler returns `[new-state outputs]`. Outputs are queued events or effect data.

Components use namespace-qualified keys. A component may read the complete entity state but may change only its key.

The entity handler runs first. Components then run in declaration order, each receiving the state produced by the preceding handler.

Entity handlers cannot change component-owned keys. This keeps reusable behavior isolated without hiding useful entity state.

## Event delivery

Messages are vectors whose first value is a keyword:

```clojure
[:player/move 10]
```

Events wrap a message with an optional target:

```clojure
{:target :player
 :message [:player/move 10]}
```

A nil target broadcasts to every entity. Any other target addresses one entity, including false values.

Each step queues external events before source events. Delivery and generated outputs use one FIFO queue.

Broadcast handlers run in entity insertion order. Their outputs join the queue in that same order.

A missing target is an error. Silent delivery would hide stale IDs and spelling mistakes.

The default event bound is 10,000 deliveries per step. Exceeding it raises structured exception data instead of hanging the game loop.

## Sources and time

An event source receives game context and `dt`, then returns sequential events. The runtime evaluates sources once at the start of a step.

`dt` must be finite and nonnegative. Randomness and wall-clock reads belong in adapters or explicit event payloads.

Equal game state, sources, `dt`, and external events must produce equal results.

## Rendering and effects

Render handlers receive entity state, game context, and render context. They return sequential data, which the core realizes into a vector.

Effects remain keyword-led vectors until `dispatch-effects!` reaches an adapter. Missing effect handlers are errors.

The Quil adapter interprets rectangles, circles, and sprites. It owns frame timing, keyboard state, assets, drawing calls, and effect execution.

## Errors

Public boundaries throw `ExceptionInfo` with a `:phase` value. Delivery errors add `:entity-id` and `:event`; component errors also add `:component-key`.

The core validates external values and trusts validated internal state. No handler may return lazy or malformed protocol data unnoticed.

## Extension boundary

Physics, networking, persistence, entity lifecycle operations, and other renderers belong in separate milestones or modules. Concrete games should establish their contracts first.
