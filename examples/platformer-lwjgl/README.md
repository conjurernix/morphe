# Skybound platformer

This example runs on the LWJGL adapter and demonstrates:

- keyboard movement and jumping with event-driven game state;
- PNG sprites generated at startup and loaded as LWJGL textures;
- projectiles spawned and despawned through deterministic lifecycle commands;
- generated jump, landing, and coin sound effects;
- particle bursts returned as ordinary render data.

Run it from this directory:

```sh
clojure -M:run
```

The run alias includes the macOS Apple Silicon LWJGL native classifiers.

Use the arrow keys or `A`/`D` to move. Press `W` or `Up` to jump. Hold `Space` to shoot. Press `Escape` to quit. The game pauses when its window loses focus.
