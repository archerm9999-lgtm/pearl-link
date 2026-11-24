# PearlLink 1.2.0 - Paper 1.21.11

Paper plugin (not a Fabric mod). Needs JDK 21 to build.

## Commands (OP only - permission `pearllink.admin`)

    /pearlrod give                      Pearl Rod for yourself
    /pearlrod give pearl [player]       Pearl Rod
    /pearlrod give orbital [player]     Orbital Rod

## Pearl Rod

1. Throw an ender pearl and keep it suspended.
2. Hold the Pearl Rod, sneak, and right-click while aiming at your pearl (up to 64 blocks). The rod is now linked.
3. Right-click (not sneaking) to pull the pearl.

ANYONE holding a linked rod can pull it - enemies included. The player who THREW the pearl is the one teleported, not the player holding the rod.
You can only link pearls you threw yourself (OPs can link anyone's).

## Orbital Rod

1. Sneak + right-click while aiming at a player to select them.
2. Right-click (not sneaking) to pull: the selected player is teleported to their own newest loaded pearl.

## Build

    mvn package        ->  target/pearl-link-1.2.0.jar

## Build with GitHub Actions

1. Unzip this project so that `pom.xml` and the `.github` folder are at the TOP of your repo (not inside another folder).
2. Push to GitHub. The workflow in `.github/workflows/build.yml` runs automatically (or use Actions > Build PearlLink > Run workflow).
3. Open the finished run and download the `PearlLink-jar` artifact. Unzip it and put `pearl-link-1.2.0.jar` in your server's `plugins` folder.
4. The workflow fails on purpose if the jar has no `plugin.yml` or no `PearlLinkPlugin.class`, so you never get an empty jar again.

## Limits

- The pearl must be loaded (its chunk active) when you pull it.
- The teleported player must be online and in the same dimension as the pearl.
- Pulling removes the pearl.
