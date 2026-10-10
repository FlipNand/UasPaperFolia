# Verification on real servers

Everything on this page is copied from an actual run, not from a test harness. Full logs are in
[`verify/evidence/`](../verify/evidence).

## What was run

| | Paper | Folia |
| --- | --- | --- |
| Build | `26.3-169` (`main@46d4834`) | `26.2-7` (`ver/26.2.x@14b7fee`) |
| Minecraft | 26.3 | 26.2 |
| Jar SHA-256 | `185d9f42…781fb` | `128a6341…2b3b3` |
| Status protocol | 777 | 776 |
| JVM | Temurin 25.0.4.1, `-Xms1G -Xmx2G -XX:+UseG1GC` | same |
| Level | flat, `online-mode=false`, port 25565 | flat, `online-mode=false`, port 25566 |
| Plugin | `uas-example-1.0.0.jar` (`23ccf2fd…f783eb`) | identical jar |

The two servers deliberately run **different Minecraft versions and different ports**, because a
verification that only ever runs one configuration proves very little.

## The startup trace

Both servers, first attempt, no retries:

```
[dev.uas.paper.PaperUasPlatform] UAS injection: walk root: CraftServer
[dev.uas.paper.PaperUasPlatform] UAS injection: server instance: DedicatedServer
[dev.uas.paper.PaperUasPlatform] UAS injection: connection listener: ServerConnectionListener
[dev.uas.paper.PaperUasPlatform] UAS injection: channels via field channels: SynchronizedRandomAccessList
[dev.uas.paper.PaperUasPlatform] UAS injection: located 1 listening channel(s)
[dev.uas.paper.PaperUasPlatform] UAS injection: hooked: KQueueServerSocketChannel[/[0:0:0:0:0:0:0:0]:25565]
[dev.uas.paper.PaperUasPlatform] UAS attached the asset multiplexer to the Minecraft listener (1 of 1 listening channel(s) hooked); packs are served on the game port, no extra port is open
[dev.uas.paper.PaperUasPlatform] UAS started on Paper with the 'low_ram' memory profile
```

Folia is byte-for-byte the same shape, differing only in the platform name and the port
(`…:25566`, `UAS started on Folia …`).

`channels via field channels` is the hop that matters. Before the locator was fixed the same
server produced this instead, thirty times, then gave up:

```
UAS injection: channels via getConnections(): SynchronizedRandomAccessList
UAS injection: the connection listener exposed no bound channels; tried [getConnections, getConnectionChannels, getListeners, getChannels, getEndpoints]
UAS found no listening channel yet (attempt 30 of 30); retrying in 10 ticks
ERROR: UAS could NOT attach to the Minecraft listener after 30 attempt(s). Asset streaming over the game port is unavailable.
```

That is what the defect looked like from the outside: a successful-looking startup, a
successfully-served *nothing*, and no pack ever delivered. The cause and fix are described in
[Architecture](Architecture.md#reaching-the-listening-socket).

## `/uas status` on a live server

Abridged; the full report is 100+ keys. **Paper:**

```
  platform: Paper
  platform.regionised: false
  portless: true
  hooked: true
  hookAttempts: 1
  bedrockPlayers: 0
  scheduler.platform: Paper
  scheduler.regionised: false
  scheduler.strategy: per-player work is serialised onto the single main thread; concurrency is limited to async work that touches no world state
  bridge.channel.name: uas:channel
  bridge.channel.registered: true
  bridge.channel.helloCapabilities: 255
  push.packPusher.baseUrl: http://127.0.0.1:25565/uas/pack/
  push.packPusher.required: false
  push.packPusher.scheduling: main thread
  engine.profile: low_ram
  engine.packs.packs: 1
  engine.packs.packIds: uas-example
  engine.packs.totalBytes: 2106
  engine.blocks.blocks: 1
  engine.blocks.availableBackends: [NOTE_BLOCK_MATRIX, TRIPWIRE_MATRIX, DISPLAY_ENTITY]
  engine.blocks.unusedBackends: [NOTE_BLOCK_MATRIX, TRIPWIRE_MATRIX]
  engine.blocks.degradedGeometry: []
  engine.sounds.sounds: 6
  engine.sounds.variants: 7
  engine.ui.schemas: 1
  engine.serverBound: false
  engine.serverHooked: false
  engine.port: 0
  sniff.connections: 7
  sniff.minecraftConnections: 1
  sniff.httpConnections: 6
  sniff.ambiguousBytes: 0
  sniff.timeoutClosed: 0
```

**Folia** differs exactly where it should:

```
  platform: Folia
  platform.regionised: true
  scheduler.platform: Folia
  scheduler.regionised: true
  scheduler.strategy: per-player work is dispatched to the owning region thread; N players in N regions are served by N threads in parallel
```

Three readings are worth calling out because they look like faults and are not:

- `engine.serverBound: false` with `portless: true` — the engine never binds its own listener on
  a real server. It attaches to the server's. A status dump showing `serverBound=false` next to
  `hooked=true` is the correct shape.
- `engine.port: 0` — the engine owns no port. The pack is on the game port.
- `engine.blocks.unusedBackends: [NOTE_BLOCK_MATRIX, TRIPWIRE_MATRIX]` — the lamp needs
  light emission and a display entity, so the note-block backend is available but deliberately
  not chosen. `block.placer.assignedStates: 1` of `matrixCapacity: 800` (0.1%).

## Both protocols on one port

| Check | Port 25565 (Paper) | Port 25566 (Folia) |
| --- | --- | --- |
| `GET /uas/pack/uas-example` | `200`, `application/zip` | `200`, `application/zip` |
| `HEAD` same URL | `200` | `200` |
| `Range: bytes=0-99` | `206`, 100 bytes | `206`, 100 bytes |
| `GET /uas/pack/missing` | `404` | `404` |
| Minecraft status handshake | `Paper 26.3`, protocol 777 | `Folia 26.2`, protocol 776 |

Response headers, identical on both:

```
HTTP/1.1 200 OK
content-type: application/zip
etag: "49624535ef0032b28e824e263bdbd625ae0df67d"
cache-control: public, max-age=31536000, immutable
accept-ranges: bytes
content-length: 2106
```

The Minecraft handshake is a real varint-framed handshake + status request, answered by the
server's own protocol handler through the multiplexed socket.

On both servers `/uas status` accounted for every connection that had touched the port:
`sniff.connections: 7` decomposing exactly into `1` Minecraft plus `6` HTTP — the two
`GET`s, the `HEAD`, the `Range` request, the `404` probe and one repeat fetch — with
`ambiguousBytes: 0`. The numbers are identical on both servers because the same sequence of
checks was run against each, which is itself the useful part: the sniffer classified every
connection correctly and nothing needed a second pass.

## The pack is now byte-identical across servers

Folia 26.2 and Paper 26.3, different JVMs, different ports, different Minecraft versions:

```
paper  sha1 49624535ef0032b28e824e263bdbd625ae0df67d  size 2106  (repeat fetch: same)
folia  sha1 49624535ef0032b28e824e263bdbd625ae0df67d  size 2106  (repeat fetch: same)
cmp: identical
```

Getting there required three fixes, and the intermediate values are the evidence that each was
real:

| Run | Paper | Folia | Cause |
| --- | --- | --- | --- |
| Before fixes | `ea99d6a6…` | `701b94be…` | `Map.copyOf` salts slot order per JVM; particle texture picked at random |
| After slot fix | `835b5048…` | `115f2b48…` | `Cuboid` still threw away its `EnumMap` face order |
| After face fix | `49624535…` | `49624535…` | identical |

Every intermediate run also differed in *content*, not just ordering: the particle texture was
`…_crystal` on one server and `…_base` on the other, purely because a map iterated differently.

## What this run did **not** verify

Stated plainly, because a verification report that only lists successes is not one:

- **No client ever connected.** There was no Minecraft client in the environment, so the pack
  push (`push.packPusher.pushes: 0`, `trackedPlayers: 0`), the HUD lifecycle, custom-packet
  round-trips, `ownership-guard` firing on a real cross-region mutation, and the in-game
  rendering of the block were all **not** exercised. What was verified is the server-side
  pipeline, the wire protocol and the HTTP surface.
- **The example pack ships no binary assets.** The project contains no `.png` and no `.ogg`
  files, so the archive is six JSON entries. The models reference
  `uas:block/crystal_lamp_base` and `uas:block/crystal_lamp_crystal` and the sound events
  reference seven `.ogg` paths, none of which exist yet — which is exactly why the plugin logs
  `UAS pack expects asset: …` for all ten at startup. The pack is structurally correct and the
  distribution path is proven, but the block will render with missing textures until those
  files are supplied.
- **The Geyser/Bedrock path is untested.** `uas-geyser` is a Geyser extension, not a plugin, and
  no Bedrock client or Geyser instance was available.
- **There is no Java HUD renderer.** `UnifiedUIManager.planFor` *chooses*
  `UiRenderPath.JAVA_HUD_OVERLAY`, but nothing in the repository turns a plan into anything a
  client draws — there is no action-bar, title, boss-bar or scoreboard code anywhere in the
  engine. The only component that consumes a `UiRenderPlan` is `BedrockUiCompiler`, and it
  rejects the Java paths. Drawing a HUD is left to the plugin author; the example registers a
  schema and calls `setState`, and never displays it. The UI layer is a *planner*, not a
  renderer.
- **Custom packets need a client-side companion that does not exist here.**
  `UasChannelBridge` documents its own transport as *"the same bytes arrive from a Java client
  (a companion mod reading plugin messages) and from a Bedrock client translated by the Geyser
  bridge"* — a Java companion mod is assumed. Every module in this repository is server-side
  (`settings.gradle.kts` includes none for a client), so on an unmodified Java client the
  `uas:channel` payloads have no handler and do nothing. Sending them is not expected to be
  harmful — Spigot/Paper track which channels a client has subscribed to and suppress such
  sends, and modern Minecraft has an unknown-payload fallback that is discarded rather than
  fatal — but that was **not** verified against this specific Paper build, and the bytes remain
  inert either way.

### So would it actually work?

The delivery does. The content does not, and for a Java player the answer as shipped is:
the pack is offered, downloaded and applied; the note-block state carries the custom model; and
the block then renders as the missing-texture pattern, because none of the textures or sounds
the pack declares exist. No HUD appears, because nothing renders one. The custom packets are
ignored, because a vanilla client has no handler for them. What is genuinely proven here is the
engine — attachment, portless serving, protocol coexistence, framing and pack identity — not the
example's visible result.
