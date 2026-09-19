# Changelog

All notable changes to Morphe are recorded here. The project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and semantic versioning.

## [Unreleased]

### Added

- Added deterministic entity lifecycle, context, and scene commands.
- Added direct immutable entity, context, and scene operations.
- Added typed entity snapshots restored through application factories.
- Added fixed-timestep scheduling with bounded catch-up and render interpolation.
- Added application events and controls for quit, pause, focus, resize, and fatal errors.
- Added versioned replay recordings and cross-platform deterministic random state.
- Added the LWJGL desktop adapter and a platformer validation game.
- Replaced immediate-mode LWJGL drawing with shader-based VBO batches and atlas regions.
- Added classpath image assets with deduplicated loading and deterministic native cleanup.
- Added stable keyboard, text, mouse, wheel, and standard gamepad input frames.
- Added OpenAL static Ogg playback with gain, pitch, looping, pause, and cleanup.

## [0.2.0] - 2026-09-18

### Changed

- Replaced the ECS and system model with entity-local state and event handlers.
- Processed external events before source events through one bounded FIFO queue.
- Made broadcast and rendering order follow explicit entity insertion order.
- Enforced namespace-qualified component keys and state ownership.
- Returned eager render data and structured errors at public boundaries.

### Added

- Added pure render handlers, event sources, targeted messages, broadcasts, and effect descriptors.
- Added the `morphe.adapters.quil` loop for keyboard input, assets, rendering, and effects.
- Added property tests, package-consumer smoke tests, and the event-driven Snake example.

### Removed

- Removed worlds, generated entity IDs, systems, queries, resources, commands, and direct component mutation.
- Removed the LWJGL adapter and old platformer examples.

[Unreleased]: https://github.com/conjurernix/morphe/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/conjurernix/morphe/releases/tag/v0.2.0
