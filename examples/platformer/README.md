# Morphe Platformer

This example is a small one-screen platformer built with Morphe's immutable ECS and Quil adapter. It demonstrates how
input, physics, collision, game state, and rendering can remain separate systems over one world value.

## Quickstart

Run the game from this directory:

```sh
cd examples/platformer
clojure -M:run
```

Run the example's logic tests:

```sh
clojure -M:test
```

The example has its own `deps.edn`. It uses local dependencies on `modules/core` and `modules/quil`, so changes to
Morphe are available without publishing a library version.

## Play the game

| Action                      | Keys                    |
|-----------------------------|-------------------------|
| Move left                   | Left arrow or `A`       |
| Move right                  | Right arrow or `D`      |
| Jump                        | Up arrow, `W`, or Space |
| Restart after a win or loss | `R`                     |

Collect the four coins, avoid the red enemy, and reach the green goal. Falling below the screen or touching the enemy
ends the run. The game records a win when the player reaches the goal; collecting every coin is optional.

## How the example is structured

The example uses a fixed 960 by 540 world. Entity positions are center points in screen coordinates: `x` increases to
the right and `y` increases downward. Rectangle bounds are stored separately from Quil shape data because physics and
collision need dimensions without depending on rendering.

`platformer.game` owns the simulation:

- `initial-world` creates the player, platforms, coins, enemy, goal, input resource, and game-state resource.
- `input-system` converts the command from Quil into a normalized set containing values such as `:left`, `:jump`, and
  `:restart`.
- `player-system` applies horizontal movement, gravity, jumping, and platform landing.
- `enemy-system` moves the enemy between its two patrol limits.
- `collection-system` destroys collected coins and increments the score.
- `outcome-system` changes the game phase to `:won` or `:lost`.
- The restart system replaces the world after a finished run.

The runtime runs command systems first, then gameplay systems in the order listed in `runtime`. That order matters:
collision and outcome checks must observe the positions produced by movement in the same frame.

`platformer.main` is the Quil boundary. Its command callback reads held keys and returns an ECS command. The adapter
renders shape components, then calls the overlay callback for the score, controls, and end-state messages.

## ECS data model

Gameplay entities use marker components such as `player-key`, `platform-key`, and `coin-key` to identify their role.
Shared Morphe components hold positions and velocities. The example's `bounds-key` stores rectangle dimensions for
collision checks.

World resources hold data shared by systems:

- `input-key` contains the normalized input for the current frame.
- `state-key` contains the phase, score, and total coin count.

Systems return a new world instead of mutating entities. Commands are the explicit boundary between Quil's event loop
and the simulation.

## Extending the level

Add a platform or coin by changing the coordinate data in `spawn-platforms` or `spawn-coins`. Use the same center-point
coordinates and rectangle dimensions as the existing entries.

To add a new behavior, create a system over the smallest useful query, add it to `runtime`, and document its position in
the system order if it depends on another system. Keep input translation in the command system and keep rendering data
on the entities that need to be drawn.

The tests in `test/platformer/game_test.clj` exercise the simulation without opening a Quil window. Add a test for each
new rule before tuning the visual presentation.

## Troubleshooting

- If dependencies do not resolve, run the command from `examples/platformer`, where the local `deps.edn` is located.
- If the window cannot open, check that the machine has a graphical display available for Quil.
- If a gameplay test fails, inspect the world returned by `morphe.core/step`; the step result contains the next `:world`
  and emitted `:events`.
