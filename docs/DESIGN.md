# Functional, Engine-Agnostic ECS for Clojure

## 1. Overview

Build a **purely functional, engine-agnostic Entity Component System (ECS)** library for Clojure, with optional
ClojureScript compatibility as a future or parallel target.

The library models a game/simulation as immutable state and provides:

* Entities
* Components
* Resources
* Queries
* Systems
* Events
* System scheduling
* Persistent world snapshots
* Efficient data-oriented execution views
* Built-in common 2D and 3D components

The library must not implement a game loop, renderer, physics engine, audio system, input system, windowing system, or
other engine-specific functionality.

The core abstraction is:

```text
immutable World
      │
      ▼
   Systems
      │
      ▼
new immutable World
```

The implementation may internally use mutable/transient/packed structures while executing systems, provided that the
externally observable API remains purely functional.

---

# 2. Design Goals

## 2.1 Primary goals

### Pure functional semantics

All persistent game state must be represented as immutable Clojure data.

Given the same world, inputs, and timestep:

```clojure
(step runtime world dt)
```

must produce the same result, assuming systems themselves are deterministic.

No global mutable game state.

No `atom`, `ref`, `agent`, or `volatile!` should be required by the core library.

---

### Engine agnostic

The library must have no dependency on:

* OpenGL
* LWJGL
* libGDX
* Godot
* Unity
* JavaFX
* Quil
* browser APIs
* audio libraries
* physics libraries

An engine adapter should be able to consume the world and events.

Example:

```clojure
(loop [world initial-world]
  (let [{:keys [world events]}
        (ecs/step runtime world dt)]

    (renderer/render renderer world)
    (audio/process-events audio events)

    (recur world)))
```

The ECS knows nothing about `renderer` or `audio`.

---

### Data oriented where useful

Components should have a storage model that allows systems to operate on component-specific data without traversing
unrelated entity state.

However, the implementation must **not sacrifice functional semantics simply to imitate C/C++ ECS architectures**.

The logical representation and execution representation are separate concepts.

---

### Persistent snapshots

Every `World` returned by a transformation must remain valid after subsequent transformations.

This should work:

```clojure
(def world-0 initial-world)

(def world-1
  (ecs/step runtime world-0 0.016))

(def world-2
  (ecs/step runtime world-1 0.016))

;; world-0 remains usable
```

Structural sharing should be relied upon wherever practical.

---

### Determinism

The architecture should make deterministic simulation straightforward.

Randomness, simulation time, and other simulation inputs should be explicit state/resources rather than hidden global
state.

Avoid internally calling:

```clojure
(System/currentTimeMillis)
(rand)
```

from simulation code.

---

# 3. Non-goals

The first version should **not** attempt to be:

* A complete game engine
* A rendering framework
* A physics engine
* A scene graph
* An asset manager
* A networking framework
* A serialization framework
* A high-performance native ECS equivalent
* A replacement for Bevy/Flecs/EnTT
* A general-purpose reactive programming framework

Performance optimizations should be introduced where measurements justify them.

---

# 4. Core Concepts

The library consists of six fundamental concepts:

```text
Entity
Component
Resource
Query
System
World
```

And two execution concepts:

```text
Event
Runtime
```

---

# 5. Entity

An entity is only an identity.

It must not contain behavior.

Prefer integer identifiers internally:

```clojure
42
```

The public API should support generated entity IDs.

Example:

```clojure
(def [world player]
  (ecs/spawn world))
```

or equivalent API.

Entities must be usable as map keys and set members.

Do not implement entities as mutable objects or Java classes unless an implementation optimization later requires it
internally.

---

# 6. Components

Components are immutable data associated with an entity.

Example:

```clojure
{:game/position
 {:x 10.0
  :y 20.0}

 :game/velocity
 {:x 5.0
  :y 0.0}

 :game/health
 {:current 100
  :maximum 100}}
```

Components contain **data only**.

They must not contain:

* System functions
* Mutable state
* Engine references
* Renderer references
* Physics engine objects
* Audio objects

---

# 7. Fully Qualified Component Keywords

Components should use **fully qualified namespaced keywords**.

Do not use generic keywords such as:

```clojure
:position
:velocity
:health
```

Prefer:

```clojure
:game/position
:game/velocity
:game/health
```

or, for library-provided components:

```clojure
:ecs2d/position
:ecs2d/velocity
```

The exact project namespace should be chosen based on the final library name.

The purpose is to avoid semantic ambiguity and component collisions.

For example:

```clojure
:physics/position
:render/position
:game/position
```

can coexist if desired.

Component keywords should therefore be treated as globally meaningful identifiers.

---

# 8. Built-in Components

The library should provide a standard set of common components.

These should remain **plain immutable Clojure data**.

They should not introduce dependencies on a rendering or physics engine.

## 8.1 2D components

At minimum:

### Position

```clojure
{:x 0.0
 :y 0.0}
```

Keyword:

```clojure
:ecs/position-2d
```

### Velocity

```clojure
{:x 0.0
 :y 0.0}
```

Keyword:

```clojure
:ecs/velocity-2d
```

### Acceleration

```clojure
{:x 0.0
 :y 0.0}
```

Keyword:

```clojure
:ecs/acceleration-2d
```

### Scale

```clojure
{:x 1.0
 :y 1.0}
```

Keyword:

```clojure
:ecs/scale-2d
```

### Rotation

Use a scalar angle.

```clojure
{:radians 0.0}
```

Keyword:

```clojure
:ecs/rotation-2d
```

Alternatively, because a scalar component is sufficient:

```clojure
0.0
```

should be considered if it produces a cleaner API.

### Transform

A transform component may combine:

```clojure
{:position ...
 :rotation ...
 :scale    ...}
```

However, avoid duplicating data unnecessarily.

The library should document whether `transform-2d` is intended as an alternative to separate position/rotation/scale
components or as a derived/serialization representation.

---

# 9. 3D Components

At minimum:

### Position

```clojure
{:x 0.0
 :y 0.0
 :z 0.0}
```

Keyword:

```clojure
:ecs/position-3d
```

### Velocity

```clojure
{:x 0.0
 :y 0.0
 :z 0.0}
```

Keyword:

```clojure
:ecs/velocity-3d
```

### Acceleration

```clojure
{:x 0.0
 :y 0.0
 :z 0.0}
```

Keyword:

```clojure
:ecs/acceleration-3d
```

### Scale

```clojure
{:x 1.0
 :y 1.0
 :z 1.0}
```

Keyword:

```clojure
:ecs/scale-3d
```

### Rotation

Use a representation that does not imply an engine-specific math library.

For general 3D orientation, quaternion data is preferable:

```clojure
{:x 0.0
 :y 0.0
 :z 0.0
 :w 1.0}
```

Keyword:

```clojure
:ecs/rotation-3d
```

### Transform

A 3D transform can contain:

```clojure
{:position ...
 :rotation ...
 :scale    ...}
```

Keyword:

```clojure
:ecs/transform-3d
```

---

# 10. Math Data

The initial implementation should use ordinary Clojure maps/vectors for built-in components rather than introducing a
mandatory vector/matrix library.

For example:

```clojure
{:x 10.0 :y 20.0}
```

rather than requiring:

```clojure
some.vector/Vec2
```

This preserves:

* serializability
* EDN compatibility
* engine independence
* inspectability
* ease of use from the REPL

A future optimized storage implementation may use primitive arrays, buffers, or specialized numeric representations
without changing the semantic component API.

---

# 11. World

`World` is the primary immutable state container.

Conceptually:

```clojure
{:entities   ...
 :components ...
 :resources  ...}
```

The exact internal representation is private.

Users should interact through API functions rather than relying on internal map structure.

A world contains:

### Entity state

Which entities exist.

### Component storage

Component values associated with entities.

### Resources

Global game/simulation state.

### Optional indexes

Indexes used to accelerate queries.

### Optional execution metadata/cache

Internal optimization structures that do not affect semantic world equality.

---

# 12. Resources

Resources are global state not associated with an entity.

Examples:

```clojure
:game/time
:game/score
:game/level
:game/input
:game/rng
```

API should support:

```clojure
(get-resource world :game/score)

(set-resource world :game/score 100)

(update-resource world :game/score inc)
```

Resources are immutable.

---

# 13. Component API

Provide operations approximately equivalent to:

```clojure
(component world entity component-key)

(has-component? world entity component-key)

(add-component world entity component-key value)

(remove-component world entity component-key)

(set-component world entity component-key value)

(update-component world entity component-key f & args)
```

Example:

```clojure
(def world'
  (ecs/add-component
    world
    player
    :ecs/position-2d
    {:x 100.0
     :y 50.0}))
```

---

# 14. Entity Lifecycle

Provide:

```clojure
(spawn world)

(destroy world entity)

(alive? world entity)
```

Potential convenience API:

```clojure
(spawn
  world
  {:ecs/position-2d {:x 0.0 :y 0.0}
   :ecs/velocity-2d {:x 1.0 :y 0.0}})
```

should return:

```clojure
[world' entity]
```

This allows construction without side effects.

---

# 15. Queries

Queries are immutable values describing required component sets.

Basic query:

```clojure
(q/all
  :ecs/position-2d
  :ecs/velocity-2d)
```

Conceptually:

```clojure
{:all #{:ecs/position-2d
        :ecs/velocity-2d}}
```

The actual representation should be private if possible.

---

## 15.1 Required components

```clojure
(q/all
  :ecs/position-2d
  :ecs/velocity-2d)
```

matches entities containing both.

---

## 15.2 Excluded components

```clojure
(q/all
  :ecs/position-2d
  (q/without :game/dead))
```

---

## 15.3 Optional components

Support optional components where useful:

```clojure
(q/all
  :ecs/position-2d
  (q/optional :ecs/velocity-2d))
```

The exact API may be refined during implementation.

---

## 15.4 Query results

A query should return entities efficiently:

```clojure
(query world
       (q/all
         :ecs/position-2d
         :ecs/velocity-2d))
```

Potential result:

```clojure
[1 5 9 20]
```

The implementation should avoid allocating unnecessary intermediate collections.

---

# 16. Data-Oriented Storage

The logical ECS should be component-oriented.

Conceptually:

```clojure
{:components
 {:ecs/position-2d
  {1 {:x 10.0 :y 20.0}
   2 {:x 20.0 :y 30.0}}

  :ecs/velocity-2d
  {1 {:x 2.0 :y 0.0}}}}
```

This provides a useful component-oriented access pattern.

However, the public API must not expose this as the required physical representation.

---

# 17. Persistent Representation vs Execution Representation

This is a core architectural requirement.

The ECS should distinguish between:

```text
Semantic World
```

and:

```text
Execution View
```

The semantic world is immutable.

The execution view may be optimized.

Conceptually:

```text
             Immutable World
                    │
                    ▼
            Execution Planning
                    │
                    ▼
        ┌────────────────────────┐
        │ temporary execution    │
        │ representation          │
        └────────────────────────┘
                    │
                    ▼
               run systems
                    │
                    ▼
            commit changes
                    │
                    ▼
             Immutable World'
```

---

# 18. Reference Sharing

Immutable component values should be shared whenever possible.

If:

```clojure
(def position {:x 10.0 :y 20.0})
```

is present in multiple derived views, they should reference the same immutable object rather than copying the map.

Conceptually:

```text
                 {:x 10 :y 20}
                       │
              ┌────────┴────────┐
              ▼                 ▼
       component storage    entity/query view
```

Do not duplicate component data merely to provide different access patterns.

---

# 19. Execution Optimization

The implementation should initially use ordinary persistent data structures.

Where profiling identifies bottlenecks, introduce:

* transients
* mutable temporary execution buffers
* packed entity indexes
* primitive arrays
* `java.nio` buffers
* specialized numeric storage

These optimizations must be implementation details.

The public semantics remain:

```clojure
world -> world'
```

---

# 20. Transient Execution

A system execution may conceptually operate as:

```text
persistent world
       ↓
transient working state
       ↓
execute many changes
       ↓
persistent!
       ↓
new immutable world
```

This avoids allocating a new persistent structure for every individual component update.

This optimization is particularly important for systems processing many entities.

---

# 21. Packed Execution

A future execution backend may represent a query as packed columns:

```text
entities:     [42 43 44 45]

position.x:   [10 20 30 40]
position.y:   [20 30 40 50]

velocity.x:   [ 2  3  1  4]
velocity.y:   [ 0  1  2  0]
```

This is an optimization, not the canonical world representation.

The implementation should not require primitive storage in version 1.

---

# 22. Systems

A system is a deterministic transformation over the world.

The simplest conceptual form is:

```clojure
(fn [world dt] world')
```

However, the ECS should provide a higher-level system abstraction that contains:

* Name
* Query requirements
* Transformation function
* Optional scheduling metadata

Example:

```clojure
(def movement
  (system
    {:name :game/movement
     :query
     (q/all
       :ecs/position-2d
       :ecs/velocity-2d)}

    (fn [world entity dt]
      ...)))
```

The exact API can be refined.

---

# 23. Entity Systems

A common system pattern should operate over matching entities.

Conceptually:

```clojure
(system
  (q/all
    :ecs/position-2d
    :ecs/velocity-2d)

  (fn [world entity dt]
    ...))
```

The runtime is responsible for:

1. Evaluating the query.
2. Iterating matching entities.
3. Calling the system operation.
4. Producing the new world.

---

# 24. System Context

Avoid forcing every system to repeatedly perform lookups such as:

```clojure
(component world entity :ecs/position-2d)
```

if an execution view can efficiently provide the relevant components.

A future API may allow:

```clojure
(fn [{:keys [position velocity]} entity dt]
  ...)
```

or:

```clojure
(fn [entity components dt]
  ...)
```

This should be designed with optimization in mind.

---

# 25. Events

Systems should be able to produce events.

For example:

```clojure
{:type   :game/entity-died
 :entity enemy}
```

A step should potentially return:

```clojure
{:world  world'
 :events [...]}
```

rather than only `world'`.

This is useful for:

* audio
* rendering effects
* UI
* networking
* logging
* gameplay reactions

Events are transient outputs and should not necessarily become persistent world state.

---

# 26. Commands

Consider separating commands from events.

Commands represent requested state changes:

```clojure
{:type   :game/damage
 :target entity
 :amount 10}
```

Events represent facts resulting from simulation:

```clojure
{:type   :game/entity-died
 :entity entity}
```

This distinction is useful but should not unnecessarily complicate version 1.

If implemented, the flow should be:

```text
input
  ↓
commands
  ↓
systems
  ↓
state transition
  ↓
events
```

---

# 27. Runtime

A runtime owns execution configuration, not game state.

Example:

```clojure
(def runtime
  (runtime
    [input-system
     gravity-system
     movement-system
     collision-system
     damage-system]))
```

Then:

```clojure
(step runtime world dt)
```

returns:

```clojure
{:world  world'
 :events events}
```

The runtime itself can be immutable.

---

# 28. Scheduling

Systems should have an explicit ordering mechanism.

The simplest initial implementation can use ordered vectors:

```clojure
[runtime-system-a
 runtime-system-b
 runtime-system-c]
```

Later support explicit dependency relationships:

```clojure
:before
{:game/collision
 #{:game/movement}}

:after
{:game/damage
 #{:game/collision}}
```

The runtime should eventually be capable of determining which systems are independent.

For example:

```text
movement ──────┐
               ├── collision ── damage
gravity ───────┘

particles ───────────────────── cleanup
```

Independent systems could potentially execute in parallel in a future backend.

---

# 29. Parallelism

Parallel execution should not be a requirement for version 1.

However, the architecture should avoid preventing it.

Systems should declare enough information about:

* components read
* components written
* resources read
* resources written

to eventually determine conflicts.

For example:

```text
System A
reads:  position
writes: velocity

System B
reads:  health
writes: particles
```

These systems are potentially independent.

This should be considered when designing the system metadata API.

---

# 30. Deterministic Randomness

Randomness should be explicit.

Avoid:

```clojure
(rand)
```

inside deterministic simulation systems.

Prefer a resource:

```clojure
:game/rng
```

or an explicit RNG state.

A system may consume RNG state and return an updated RNG state as part of the world.

This enables deterministic replay.

---

# 31. Time

Simulation time should be represented explicitly when needed.

Potential resources:

```clojure
:game/time
:game/delta-time
:game/frame
```

The ECS itself should not depend on wall-clock time.

`dt` may be supplied directly to:

```clojure
(step runtime world dt)
```

---

# 32. Serialization

Because the semantic world consists primarily of Clojure data, it should be straightforward to serialize.

The core library should avoid making serialization a hard dependency.

EDN compatibility is desirable.

For example:

```clojure
(pr-str world)
```

should be possible if the internal world representation is appropriately designed.

---

# 33. Testing Requirements

The project should have extensive property-based tests.

Important invariants:

### Immutability

Executing a system must not alter the original world.

```clojure
(let [world' (step runtime world dt)]
  ;; world remains valid
  ...)
```

### Entity lifecycle

```text
spawn → entity exists
destroy → entity does not exist
```

### Components

```text
add → has
remove → does not have
set → correct value
update → correct transformation
```

### Query correctness

For arbitrary worlds:

```text
query(all A B)
```

must return exactly entities having both A and B.

### Persistence

Old worlds must remain semantically unchanged after deriving new worlds.

### Determinism

```clojure
(step runtime world dt)
=
(step runtime world dt)
```

for deterministic systems.

---

# 34. Performance Requirements

Do not establish arbitrary throughput requirements before benchmarking.

Instead, establish benchmarks for:

1. World creation
2. Entity creation
3. Component insertion
4. Component lookup
5. Component update
6. Query
7. Iterating 1k entities
8. Iterating 10k entities
9. Iterating 100k entities
10. Updating a common component across 100k entities
11. Complete simulation step

Compare:

* persistent representation
* transient execution
* packed execution where implemented

The optimization strategy should be driven by benchmark results.

---

# 35. Important Performance Principle

Do not optimize for C/C++-style ECS memory layouts merely because they are conventional.

Clojure's runtime characteristics are different.

The primary optimization hierarchy should be:

```text
1. Correct semantic model
2. Avoid unnecessary allocations
3. Efficient query/index implementation
4. Transient batch updates
5. Efficient execution views
6. Primitive/packed storage where justified
```

Do not prematurely introduce archetypes or native buffers.

---

# 36. API Philosophy

The API should feel idiomatic to Clojure.

Prefer:

```clojure
world'
```

over:

```clojure
world->new-world
```

Prefer plain data.

Prefer functions over classes.

Prefer immutable values.

Prefer composition.

Prefer keywords over registration-heavy component classes.

Avoid:

```clojure
(defcomponent Position ...)
```

unless registration is required for a specific optimization.

---

# 37. Suggested Namespace Structure

Assuming the library's final name is `morphe`:

```text
src/
└── morphe/
    ├── core.clj
    ├── world.clj
    ├── entity.clj
    ├── component.clj
    ├── resource.clj
    ├── query.clj
    ├── system.clj
    ├── runtime.clj
    ├── event.clj
    ├── storage.clj
    ├── execution.clj
    │
    └── components/
        ├── common.clj
        ├── 2d.clj
        └── 3d.clj
```

Potential public API:

```clojure
(require '[morphe.core :as ecs])
(require '[morphe.query :as q])
(require '[morphe.system :as system])
(require '[morphe.components.2d :as c2d])
(require '[morphe.components.3d :as c3d])
```

---

# 38. Built-in Component API

The component namespaces should expose constants/functions for component keys.

For example:

```clojure
c2d/position
;; => :morphe/position-2d

c2d/velocity
;; => :morphe/velocity-2d
```

Users can then write:

```clojure
(q/all
  c2d/position
  c2d/velocity)
```

rather than repeatedly typing keywords.

The actual keywords should remain fully qualified.

For example:

```clojure
:morphe/position-2d
:morphe/velocity-2d
:morphe/acceleration-2d
```

---

# 39. Component Constructors

Provide convenient constructors:

```clojure
(c2d/position 10.0 20.0)

(c2d/velocity 5.0 0.0)

(c3d/position 10.0 20.0 30.0)

(c3d/velocity 5.0 0.0 0.0)
```

which return plain data.

Example:

```clojure
(c2d/position 10.0 20.0)

;; => {:x 10.0 :y 20.0}
```

Constructors are convenience only; users should be free to construct the data manually.

---

# 40. Component Validation

Do not require a validation library in the core.

Optionally provide Malli schemas in a separate namespace/dependency.

For example:

```clojure
morphe.components.2d.schema
```

could define:

```clojure
Position
Velocity
Acceleration
```

This keeps the core lightweight while providing stronger development-time validation when desired.

---

# 41. Engine Adapter Model

An engine integration should be external.

Example:

```text
forma
 │
 ├── libgdx adapter
 ├── lwjgl adapter
 ├── quil adapter
 ├── browser adapter
 └── custom engine adapter
```

These adapters consume:

```clojure
World
```

and:

```clojure
Event
```

but do not modify the ECS's semantic model.

A renderer might query:

```clojure
(q/all
  c2d/position
  render/sprite)
```

and produce engine-specific rendering commands.

---

# 42. Rendering Should Be External

Do not introduce:

```clojure
:renderable
```

as a mandatory concept.

A rendering library can define its own component:

```clojure
:my-renderer/sprite
```

The ECS should only understand that it is component data.

This preserves engine independence.

---

# 43. Physics Should Be External

Likewise, the core library should not define physics bodies.

A physics integration may define:

```clojure
:physics/body
:physics/collider
:physics/mass
```

The core ECS does not need to know their semantics.

Common generic components such as:

```clojure
:morphe/position-2d
:morphe/velocity-2d
```

may be used by physics systems.

---

# 44. Example

A small game could look approximately like:

```clojure
(require
  '[morphe.core :as ecs]
  '[morphe.query :as q]
  '[morphe.system :as system]
  '[morphe.components.2d :as c2d])

(defn movement-system
  [world entity dt]
  (let [position (ecs/component world entity c2d/position)
        velocity (ecs/component world entity c2d/velocity)]

    (ecs/set-component
      world
      entity
      c2d/position
      {:x (+ (:x position) (* (:x velocity) dt))
       :y (+ (:y position) (* (:y velocity) dt))})))

(def movement
  (system/entity-system
    {:name  :game/movement
     :query (q/all c2d/position c2d/velocity)}
    movement-system))

(def runtime
  (system/runtime [movement]))

(def world
  (-> (ecs/world)
      (ecs/spawn
        {c2d/position {:x 0.0 :y 0.0}
         c2d/velocity {:x 10.0 :y 0.0}})))

(def result
  (ecs/step runtime world 0.016))
```

The exact API can change during implementation, but the semantic model should remain.

---

# 45. Version 1 Scope

The first implementation should contain:

### Required

* Immutable world
* Entity IDs
* Spawn/destroy
* Components
* Resources
* Component queries
* Required/excluded query predicates
* Entity systems
* Ordered runtime
* Pure `step`
* Persistent world snapshots
* Basic event output
* Built-in 2D components
* Built-in 3D components
* Unit tests
* Property-based tests
* Benchmarks

### Optional

* Optional components
* System metadata
* Query caching
* Transient execution
* Explicit commands
* System dependency graph

### Defer

* Archetypes
* Primitive component storage
* Native code
* GPU execution
* Parallel systems
* Networking
* Serialization framework
* Physics
* Rendering
* Asset management

---

# 46. Architectural Invariants

The following should be treated as hard requirements.

### Invariant 1

The semantic world is immutable.

### Invariant 2

No engine-specific types appear in the core.

### Invariant 3

Components are data, not objects with behavior.

### Invariant 4

Component identifiers are fully qualified namespaced keywords.

### Invariant 5

Systems are deterministic transformations unless explicitly designed otherwise.

### Invariant 6

Execution optimizations must not alter semantic behavior.

### Invariant 7

Old world snapshots remain valid.

### Invariant 8

Component values should be structurally shared whenever possible.

### Invariant 9

The library must not require mutable global state.

### Invariant 10

The canonical state representation and optimized execution representation are conceptually separate.

---

# 47. Suggested Project Structure

```text
morphe/
├── README.md
├── LICENSE
├── CHANGELOG.md
│
├── modules/
│   ├── core/
│   │   ├── deps.edn
│   │   ├── src/morphe/
│   │   ├── test/morphe/
│   │   └── benchmark/morphe/
│   ├── quil/
│   │   ├── deps.edn
│   │   ├── src/morphe/adapters/quil.clj
│   │   └── test/morphe/
│   └── lwjgl/
│       ├── deps.edn
│       ├── src/morphe/adapters/lwjgl.clj
│       └── test/morphe/
│
└── examples/
    ├── platformer/
    └── lwjgl/
```

The root is a workspace coordinator. Each module owns its Clojure CLI configuration and depends on the modules below
it through local dependencies. Adapter dependencies never enter the core module.

---

# 48. Implementation Strategy

Implement in this order:

## Phase 1 — Semantic core

Implement:

```text
World
Entity
Component
Resource
```

with simple persistent Clojure data structures.

Do not optimize.

---

## Phase 2 — Queries

Implement:

```text
all
without
optional
```

and efficient component indexes.

Verify correctness extensively.

---

## Phase 3 — Systems

Implement entity-oriented systems and:

```clojure
(step runtime world dt)
```

---

## Phase 4 — Events

Add:

```clojure
{:world  ...
 :events ...}
```

---

## Phase 5 — Built-in components

Implement 2D and 3D component namespaces.

Keep their representation plain and engine-independent.

---

## Phase 6 — Benchmarks

Benchmark realistic simulations.

Do not optimize based on theoretical ECS assumptions.

---

## Phase 7 — Execution optimization

If benchmarks justify it, introduce:

```text
transient execution
       ↓
packed query views
       ↓
specialized storage
```

while preserving the same public API.

---

# 49. Design Principle

The central principle of the project should be:

> **The World is the truth; every other representation is an optimization.**

A renderer, physics engine, query cache, packed component array, transient execution structure, or scheduler must never
become the authoritative definition of the game.

The semantic state should remain representable as immutable Clojure data.

This makes the ECS suitable not only for games but also for:

* deterministic simulations
* replay systems
* rollback
* server-side simulation
* property-based testing
* AI environments
* headless simulations
* debugging/time travel
* distributed simulation

while retaining the option of optimized execution when required.
