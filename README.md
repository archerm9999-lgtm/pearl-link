# PearlLink — Paper 1.21.11

This is a Paper plugin (not a Fabric mod). It is designed for a Paper 1.21.11 server.

## Controls

1. Hold a fishing rod in your main hand.
2. Sneak + right-click the actual thrown ender pearl entity.
3. The rod stores that pearl's UUID, world UUID, and original thrower's UUID.
4. Right-click in the air or on a block while holding that rod.
5. The original thrower is teleported to the pearl, even if a different player activates the rod.

This implementation handles the exact entity supplied by `PlayerInteractEntityEvent`; it does not search within 8 blocks or use Better Stasis's proximity detection.

## Build

Requirements: JDK 21 and Maven 3.9+.

From this folder run:

```bash
mvn package
```

The plugin jar will be at:

```text
target/pearl-link-1.0.0.jar
```

## Install

1. Stop the Paper server and back up the world.
2. Put `pearl-link-1.0.0.jar` in the server's `plugins` folder.
3. Start the server.
4. Test with one thrower and one pearl before using it on important stasis chambers.

No client mod is required.

## Important limitations of this first build

- This is source code, not a compiled or runtime-tested jar.
- The pearl must still exist and be loaded when the rod is activated. This plugin does not yet force-load chunks.
- The original thrower must be online and in the same world/dimension as the pearl when activated.
- If another plugin cancels or replaces entity interaction before this plugin receives it, pairing may not occur.
- This plugin does not itself freeze a pearl in place or change Better Stasis's pearl physics. It only pairs and activates a pearl entity that already remains suspended.
- Paper's event API exposes the clicked entity directly, which is why this avoids nearby-pearl scanning.
