# Nether Client: in-game client

The part of Nether Client that runs inside Minecraft: the HUD, the menus, performance options, capes and cosmetics,
lyrics, screenshots and the rest. It is a `java.lang.instrument` agent, **not a mod**. It needs no Fabric, Forge or
any other loader, and it runs the same on vanilla and every loader, from 1.8.9 (LWJGL 2) through 1.13+ (LWJGL 3) to
26.3+ (SDL3).

The launcher lives in [clapbxt/nether-client](https://github.com/clapbxt/nether-client). It installs this client
into every instance and keeps it up to date on its own.

## Layout

| Path | What |
|---|---|
| `src/dev/nether/agent` | Bootstrap and the ASM hooks (frame end, input, chat, game arguments) |
| `src/dev/nether/ov` | The overlay: `platform`, `game`, `framework`, `ui`, `menu`, and one `features.*` package per feature |
| `res/` | Fonts, name tables, brand art, icons, title panoramas |
| `tools/` | `Relocate.java` (shades ASM), `names.js` (name tables), `SignClient.java` (release signing) |
| `docs/modules.md` | How to add a module |
| [SAFETY.md](SAFETY.md) | What the client may and may not touch in the game |

## Building

Needs a JDK 17+.

```
gradlew.bat build        (Windows)
./gradlew build          (Linux, macOS)
```

`build/` then holds:

- `nether-agent.jar` and `nether-agent-boot.jar` (the boot jar must sit beside the agent jar)
- `client.json` with the version, protocol and sha256 of both jars
- `schema.json` with every module and setting

To try a local build in the launcher, start a dev build of the launcher with `NETHER_AGENT` set to the path of
`build/nether-agent.jar`.

## Version and protocol

`gradle.properties` holds two numbers:

- `clientVersion` is this client's version. Bump it for every release.
- `clientProtocol` is the launcher ↔ client contract: the `-Dnether.*` properties the launcher passes, the boot jar
  beside the agent jar, and `nether-media.exe` beside it. A launcher only installs a client whose protocol equals its
  own `PROTOCOL` (`crates/core/src/agent.rs` in the launcher repo). Raise it only for a breaking change, and release
  a launcher with the same number at the same time. Otherwise players on the old launcher keep the last compatible
  client, which is safe but means they don't get the update.

## Releasing

1. Move the **Unreleased** lines in [CHANGELOG.md](CHANGELOG.md) under the new version, and set `clientVersion` in
   `gradle.properties` to it.
2. Merge to `main`, then tag and push: `git tag v1.0.1 && git push origin v1.0.1`.
3. The `release` workflow builds the client, checks that the tag matches `clientVersion`, signs `client.json` with the
   `CLIENT_SIGNING_KEY` secret, and publishes a GitHub release with the jars, `client.json`, `client.json.sig` and
   `schema.json`.

Players get the new client the next time they open the launcher. Each launcher start checks the latest release. If
it's newer and compatible, the launcher verifies the signature and the jar hashes, then installs it into every
instance. An instance with its game running gets it at its next launch.

### Signing key (one-time setup)

```
java tools/SignClient.java keygen
```

This prints a key pair. Put the private key in this repo's Actions secret `CLIENT_SIGNING_KEY`, and put the public key
on its own line in the launcher's `crates/core/res/client-ed25519.pub`. Keep a copy of the private key somewhere
safe and offline. A launcher only trusts keys listed in that file, so a lost key needs a launcher release with a new
public key.

## License

Source-available, see [LICENSE](LICENSE).
