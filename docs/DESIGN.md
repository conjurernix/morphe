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

Lifecycle, context, and scene commands apply after every target handles the current event. A spawned entity does not receive the broadcast that spawned it. A despawned entity finishes the current broadcast.

Commands apply before the next queued event. Later events observe the resulting entity set and context.

A missing target is an error. Silent delivery would hide stale IDs and spelling mistakes.

The default event bound is 10,000 deliveries per step. Exceeding it raises structured exception data instead of hanging the game loop.

## Sources and time

An event source receives game context and `dt`, then returns sequential events. The runtime evaluates sources once at the start of a step.

`dt` must be finite and nonnegative. Randomness and wall-clock reads belong in adapters or explicit event payloads.

Equal game state, sources, `dt`, and external events must produce equal results.

## Fixed updates

`morphe.fixed-step` owns an immutable accumulator. It clamps accepted wall time, limits catch-up updates, and reports dropped time.

Mapped input waits for the next fixed update. The first update in a catch-up frame consumes it once. Every update evaluates sources with the exact fixed delta.

The scheduler retains the previous game and returns an interpolation alpha. Adapters pass both values through render context without changing simulation state.

## Application lifecycle

`morphe.application` represents close requests, focus changes, and framebuffer resizes as data. Its reducer updates built-in state before calling the game callback.

The callback owns a plain application-state map. It may emit mapped game events and ordered controls for quit, close cancellation, pause, or resume.

Focus loss does not imply pause. Games choose that policy in the callback. Pausing resets accumulated time and ignores gameplay input until resume.

Adapters release native resources after fatal errors. An error callback chooses clean close or propagation; propagation is the default.

## Replay and random state

Replay records mapped events at the fixed tick that consumes them. Raw device events stay at the adapter boundary.

A recording contains a format version, update rate, initial snapshot, ordered tick records, and final snapshot. Playback restores typed entities and rejects a different final snapshot.

`morphe.random` uses xoshiro128** with four unsigned 32-bit words. Every draw returns the next state and value. Games store that state in snapshot data.

Replay compatibility covers the same game build and snapshot schema. Snapshot migrations remain the game's responsibility.

## Rendering and effects

Render handlers receive entity state, game context, and render context. They return sequential data, which the core realizes into a vector.

Effects remain keyword-led vectors until `dispatch-effects!` reaches an adapter. Missing effect handlers are errors.

The Quil and LWJGL adapters interpret rectangles, circles, and sprites. Adapters own clocks, windows, input, assets, drawing calls, and effect execution.

LWJGL compiles render data into triangles. It preserves layer and insertion order while merging adjacent commands that use the same texture.

The renderer uses a shader, one streaming VBO, and a reusable native buffer. Image assets retain GPU texture metadata and support pixel atlas regions.

Classpath resources take priority over filesystem paths. Equal descriptors load once, and adapter shutdown releases each native resource once.

GLFW callbacks produce immutable input frames with held state and ordered events. Games map those device events before they enter fixed updates or replay.

OpenAL buffers static Ogg Vorbis sounds. Sources support gain, pitch, looping, application pause, and deterministic cleanup.

## Errors

Public boundaries throw `ExceptionInfo` with a `:phase` value. Delivery errors add `:entity-id` and `:event`; component errors also add `:component-key`.

The core validates external values and trusts validated internal state. No handler may return lazy or malformed protocol data unnoticed.

## Lifecycle and scenes

Direct operations immutably add, remove, or replace entities and update context. Handler outputs can spawn, despawn, replace an entity, change context, or replace the complete scene.

Replacement preserves an entity's insertion position. Scene replacement installs a context map and ordered entity pairs.

## Snapshots

Typed entities have a stable keyword, symbol, or string type. A snapshot stores context plus each entity's ID, type, state, and order.

Restore accepts factories keyed by entity type. A factory receives saved state and rebuilds the entity's handlers, components, and renderer.

Snapshots are plain Clojure data. Storage encoding and migrations remain application responsibilities.

## Extension boundary

Physics, networking, storage adapters, snapshot migrations, and other renderers belong in separate milestones or modules. Concrete games should establish their contracts first.
