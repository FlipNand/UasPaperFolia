# UAS — Unified Asset Streaming Engine

Custom blocks, custom sounds and declarative UIs, declared **once** and delivered to Java
and Bedrock clients — over the server's existing Minecraft port, within a 5 GiB heap budget,
on both Paper and Folia.

```
                     one declaration  (uas-api)
                             │
        ┌────────────────────┼─────────────────────┐
        │                    │                     │
   uas-core             uas-paper              uas-geyser
 pack compiler        Java renderer          Bedrock renderer
 state matrix      block states + display    geometry.json + block
 UI planner         entities, pack push       definitions, sounds,
 sound registry     channel bridge            custom UI JSON
        │                    │                     │
        └────────┬───────────┘                     │
                 │                                 │
            uas-netty                         Geyser's own pack
   HTTP multiplexer on the                    delivery (Bedrock
   Minecraft listener                         speaks no MC protocol)
                 │
           uas-protocol  (one binary format, both client families)
```

## Documentation

The [wiki](wiki/Home.md) is the operator-facing documentation:

- [Getting Started](wiki/Getting-Started.md) — install, confirm injection, fetch the pack
- [Configuration](wiki/Configuration.md) — every key, and what the defaults do
- [Architecture](wiki/Architecture.md) — portless serving, the reflective walk, the platform seam
- [Verification](wiki/Verification.md) — a recorded run on real Paper 26.3 and Folia 26.2
- [Troubleshooting](wiki/Troubleshooting.md) — every failure mode this project has hit

The installable plugin is at [`dist/uas-example-1.0.0.jar`](dist/uas-example-1.0.0.jar), with
the library jars and a `SHA256SUMS.txt` beside it. The verification environment — launchers,
configs and the full server logs — is in [`verify/`](verify/README.md).

## Modules

| Module | Responsibility |
| --- | --- |
| `uas-api` | The declarative model: blocks, geometry, physics, sounds, UI, pack formats. No runtime dependencies — it is embedded verbatim in a Paper plugin and a Geyser extension. |
| `uas-protocol` | The shared frame format and packet set (`uas:channel`). One encoder, one decoder, both client families. |
| `uas-netty` | The portless asset transport: protocol sniffer, HTTP switch, chunked/range/ETag streaming, off-heap mmap asset source, allocator geometry. |
| `uas-core` | Pack compilation (disk-backed), note-block state allocation, sound registry, UI planner, the `UasEngine` facade. |
| `uas-paper` | The Paper/Folia platform: scheduler bridge, listening-socket injection, Java block placement, pack push, plugin-message bridge. |
| `uas-geyser` | Geyser extension plus the JIT Java→Bedrock translators (geometry, block definitions, sounds, UI). |
| `uas-example` | A production-verification plugin: shaped animated block, five sounds, bound HUD, interaction packets. |

Bytecode targets: the reusable engine is **Java 21**; `uas-paper` and `uas-example` target
**Java 25**, because Paper 26.3's module metadata declares `org.gradle.jvm.version = 25`. That
split is deliberate — nothing in the engine needs newer bytecode, and keeping it at 21 keeps
the model and the codec loadable from older tooling.

---

## Folia support, and why it is faster with more players

This is the part of the design that is not a portability footnote. Paper has **one** main
thread that owns every world, entity and block. Folia replaces it with **region threads**,
each owning a slice of the world. Almost every naive plugin is written as "do this on the
main thread", which on Folia is either illegal (`BukkitScheduler` throws) or — worse — legal
but *serialising*, throwing away the entire reason to run Folia.

The engine has exactly one seam where the platform is allowed to matter:
[`UasScheduler`](uas-paper/src/main/java/dev/uas/paper/platform/UasScheduler.java). Every
thread hop in the engine goes through it, expressed in terms of what the caller actually
knows — a player, a location, or "nothing in particular".

### Detection is by class, not by version string

[`ServerPlatform`](uas-paper/src/main/java/dev/uas/paper/platform/ServerPlatform.java)
probes for `io.papermc.paper.threadedregions.RegionizedServer`. That class exists only on
Folia. The scheduler *interfaces* (`RegionScheduler`, `EntityScheduler`, …) are shipped by
plain paper-api too, so they would detect Folia everywhere and are useless as a marker; a
`Bukkit.getVersion()` string is worse still — cosmetic, forgeable by forks, and getting the
answer wrong means either a `NoSuchMethodError` on the first pack push or a silent fall
back to one thread on a server with hundreds.

Resolution is non-initialising (`Class.forName(name, false, loader)`): the check must not run
a static initialiser, because on Folia that class wires up the regioniser.

### The four scheduling primitives, and what each maps to

| Engine call | Paper | Folia | Used for |
| --- | --- | --- | --- |
| `runForPlayer` | main thread | **entity scheduler** | pack push, HUD update, plugin message |
| `runAt` | main thread | **region scheduler** | block placement, display entity spawn |
| `runGlobal` | main thread | global region thread | reload coordinator, registry sweep |
| `runAsync` | async pool | async scheduler | pack compilation, hashing |
| `runGlobalDelayed` | `runTaskLater` | `GlobalRegionScheduler#runDelayed` | injection retry, periodic sweep |

`ownsCurrentThread` is included so a caller can skip a hop it does not need, and
`JavaBlockPlacer` uses it to *assert* region affinity — on Folia, mutating a block from the
wrong thread corrupts world state rather than throwing, so the check has to be ours.

### Where the scaling actually comes from

Per-player work is dispatched to **that player's owning region thread**. Two players standing
in different regions have their pack push, their HUD update and their display-entity movement
executed **on two threads at the same time**. On Paper the same two operations queue behind
each other in one thread. That difference is the whole argument, and it is structural rather
than a measured benchmark: the mapping is per-entity, so the concurrency the work can achieve
is bounded by region count, not by one thread.

The second half of the argument is
[`PlayerWorkQueue`](uas-paper/src/main/java/dev/uas/paper/platform/PlayerWorkQueue.java). A
burst of updates is a trap on both platforms, for opposite reasons: on Paper each `runTask` is
an insertion into one contended queue, and on Folia each `EntityScheduler#execute` carries
region bookkeeping and lands in that region's task queue, where a flood can push the region
through its tick budget. Both are fixed the same way — **one scheduled drain per player per
tick**, with every subsequent task appended to that player's mailbox and executed by the
drain. The `workQueue.coalescingRatio` counter makes the saving observable rather than
assumed; the tests assert 50 updates for one player arm exactly **one** task and that all 50
still run, in order.

A third Folia-specific correctness point: `EntityScheduler#execute` returns `false` when an
entity has been retired and the task then never runs. That latch is released and the batch
dropped, and the rejection is **counted** — because a server where every per-player task is
rejected is a server whose players see no pack at all, and that must not be silent.

### Declaring it

The example plugin ships `folia-supported: true`. Without it Folia refuses to load the plugin
at all, regardless of how carefully the code uses region schedulers: the flag is how a plugin
declares that it never assumes a main thread.

---

## Constrained RAM (≤ 5 GiB)

`MemoryProfile` buckets on the *heap the engine actually has* (`Runtime.maxMemory()`), with
`low-memory-mode: true` to force it. At ≤ 5 GiB it selects `LOW_RAM`: 16 KiB copy buffers,
deflate level 3, index flushed every 32 entries, `UasHttpConfig.lowRam()` with 16/128 KiB
chunks, and a pooled-allocator geometry chosen to bound rather than maximise throughput.

`LowMemoryPackCompiler` never holds the asset tree: entries are copied straight from their
source to the deflating stream through a fixed buffer, the SHA-1 is computed incrementally,
and the archive is written to a scratch file and atomically renamed.

## Portless delivery

`PaperConnectionInjector` reaches the server's own listening socket by reflection and inserts
the sniffer **immediately before Netty's `ServerBootstrapAcceptor`** — the only point that runs
before the child pipeline is populated. A connection whose first bytes are `GET ` is switched
to an HTTP stack; anything else detaches the sniffer and flows into the untouched vanilla
pipeline. No second port, no firewall change, no separate web server to supervise.

Injection is retried on the global scheduler, because a plugin can enable before the server has
bound. Every attempt records a trace naming the hop that failed, so a build where the accessor
names changed logs exactly what broke instead of quietly serving no packs.

## Bedrock JIT conversion, and what it loses

The three translators in `uas-geyser/jit` are **pure functions** — no Geyser types, no server,
no I/O — because a coordinate mapping is exactly the thing that must be asserted on exact
numbers. They report what they cannot keep rather than dropping it silently:

| Conversion | Loss | Reported as |
| --- | --- | --- |
| Java element rotation | Bedrock's axis sense differs; the element is emitted as its rotated bounding box | `lossy`, named in `notes()` |
| Weighted sound variants | Bedrock picks uniformly; weighted variants are **expanded** so probabilities match exactly | expansion count per event |
| Sound pitch range | Bedrock takes a fixed pitch per entry; the midpoint is used | named in `notes()` |
| Multi-box collision | Bedrock's `collision_box` is one box; the union is used | named in `notes()` |
| UI images / progress bars in a form | Forms are title + body + buttons only | named, with the element id |
| Slot grids on Bedrock | No Bedrock surface can own server-side slots | escalates to the action-bar path |

Coordinates are pinned by tests: Java `0..16` from the minimum corner → Bedrock `[-8,0,-8]`
centred on x/z, size unchanged, in 1/16 units.

Bedrock packs are handed to **Geyser**, not fetched from the multiplexer — a Bedrock client
speaks no Minecraft protocol, so an HTTP URL would be a lie. The generated pack is compiled by
the same `LowMemoryPackCompiler` the Java side uses.

## Cross-platform packets

One channel, `uas:channel`, carrying the engine's own frame format, used by a Java companion
and by the Geyser bridge alike — so there is one encoder and one decoder rather than two that
drift. Inbound frames are length- and magic-checked *before* decoding (a plugin message is
client-controlled), listeners are invoked on the player's owning thread, and client state
writes are accepted only for keys the schema actually binds.

---

## Building and verifying

```bash
./gradlew build        # compiles everything, runs all tests, produces the fat plugin jar
./gradlew :uas-example:jar   # the drop-in jar: uas-example/build/libs/uas-example-1.0.0.jar
```

### What is verified here

`./gradlew test --rerun-tasks --no-build-cache` passes with **194 tests, 0 failures, 0
skipped** — executed, not restored from the build cache:

| Module | Tests |
| --- | --- |
| `uas-netty` | 60 |
| `uas-core` | 65 |
| `uas-paper` | 35 |
| `uas-geyser` | 21 |
| `uas-example` | 13 |

Highlights, all asserted rather than described:

- **A real Paper 26.3 and a real Folia 26.2 server**, both attached on the first attempt, with
  both HTTP and the Minecraft protocol answered on the same port and the compiled pack
  byte-identical between the two. The full logs are in [`verify/evidence/`](verify/evidence) and
  written up in [Verification](wiki/Verification.md).

- **Protocol multiplexing over real loopback sockets** (`uas-netty`) — `ServerBootstrap` →
  acceptor → child pipeline, i.e. the same path production uses, including a raw-bytes probe
  that checks the exact bytes the client would see.
- **Constrained-RAM compilation in a forked `-Xmx48m` JVM** over a multi-hundred-MiB asset
  set (`ForkedLowRamCompileTest`, `LowMemoryPackCompilerTest`).
- **Folia scheduling semantics** (`uas-paper`): coalescing (50 updates → 1 task, all 50 run,
  in order), one drain per player, rejection releasing the latch so a player is not stranded,
  failure isolation inside a batch, and the tracked set returning to zero over 200 players.
- **Injection reflection walk** (`PaperChannelLocatorTest`): six accessor shapes, including
  an array container, a bare `Channel`, a root that is itself the listener, and the
  give-up-with-an-actionable-trace case.
- **Note-block state allocation** (`uas-core`): all 800 states used exactly once, order
  independence, probe rarity at 0.25 load factor, and overflow rejected rather than aliased.
- **Bedrock translations** (`uas-geyser`): exact `[-8,0,-8]`/`[16,16,16]` geometry, byte-stable
  JSON, weighted expansion 3+1 → 4 entries, streaming preserved, `sounds/…` paths with no
  extension, and a collision between two Java sound keys rejected.
- **The example end to end** (`uas-example`): the finished archive is opened and parsed, and
  the `note_block` variant key in the pack is asserted to equal the state the placer sets —
  the check that catches the one failure mode that is otherwise invisible until a player says
  "it looks wrong".

The fat jar contains 178 engine classes, `plugin.yml`, `config.yml`, and **zero** shaded
`org/bukkit`, `net/minecraft`, `io/papermc`, `org/geysermc` or `io/netty` classes: Gson is the
only third-party library it carries, and Netty deliberately is not, because the server already
provides it and a second copy on the classpath is a deadlock waiting to happen.

### What is *not* verified here, and should be before production

Stated plainly rather than implied:

1. **No Minecraft client ever connected.** Paper 26.3 and Folia 26.2 were both run and the
   server-side pipeline, the wire protocol and the HTTP surface were all exercised — but with no
   client, the pack *push* (`push.packPusher.pushes: 0`), the HUD lifecycle, custom-packet
   round-trips, `ownership-guard` firing on a cross-region mutation, and the in-game rendering
   of the block all remain unverified. What was driven by a real client was the status handshake
   on the multiplexed socket. Read `hooked` and `hookAttempts` in `/uas status` after your first
   real join; if `hooked` is false the trace names the hop that broke.
2. **No Bedrock client rendered the converted models.** The numbers are pinned by tests and
   the conventions are documented, but client-side appearance is unverified. The one
   deliberate gap is native Bedrock cube rotation, which is baked to a bounding box rather
   than emitted, because its axis sense could not be validated here.
3. **No cross-platform packet round trip through a real Geyser session.**

   Related, and worth stating before you build on the packet layer: the wire protocol assumes a
   **Java companion mod**. `UasChannelBridge` describes its transport as *"the same bytes arrive
   from a Java client (a companion mod reading plugin messages) and from a Bedrock client
   translated by the Geyser bridge"*, and no such client-side mod exists in this repository —
   every module here is server-side. On an unmodified Java client the `uas:channel` payloads
   have no handler. Sending them is not expected to disconnect anyone (Spigot/Paper suppress
   sends on channels a client has not subscribed to, and modern Minecraft discards unknown
   payload ids rather than failing), but the bytes do nothing until that mod is written.
4. **There is no Java HUD renderer.** `UnifiedUIManager.planFor` chooses
   `UiRenderPath.JAVA_HUD_OVERLAY`, but nothing in the engine turns a plan into a packet a
   client draws — no action bar, title, boss bar or scoreboard code exists, and the only
   consumer of a `UiRenderPlan` is `BedrockUiCompiler`, which rejects the Java paths. Rendering
   is left to the plugin author; the example registers a schema and never displays it.
5. **The example ships no binary assets.** The pack is six JSON files: the models reference
   `uas:block/crystal_lamp_base` and `uas:block/crystal_lamp_crystal`, and `sounds.json`
   references seven `.ogg` files, none of which exist in the project. The distribution path is
   proven; the block will render with missing textures and play silence until they are added.
6. **`api-version` in `plugin.yml`** must match the target server release.
7. **Standalone Geyser has no declaration channel.** `UasCatalog` supports the co-located case
   (Geyser-Spigot in the same JVM as the plugin) only; serialising declarations across a
   process boundary is left as an explicit follow-up rather than half-implemented, because a
   manifest that silently mismatched the Java side would produce Bedrock blocks that are wrong
   in ways nobody sees until a player walks into them.

### Bugs found and fixed while building this

- `UiSchema`'s compact constructor called `elementList()`, which reads the not-yet-assigned
  record field — every schema built through the builder failed with an NPE.
- `BlockGeometry.Builder.texture("#all", …)` stored the key with its `#` while resolution
  looks it up without — a geometry could not resolve its own textures.
- `UnifiedUIManager.planFor` checked "native Bedrock presentation" before slot ownership, so a
  slot-bearing schema was rendered as a form that silently dropped its slots.
- `UasScheduler` counters conflated scheduling failures with player disconnects.

And these, found only by running a real server — which is the argument for running one:

- **Injection could never attach on Paper 26.3 or Folia 26.2.** The walk tried accessor names
  only, but the listening `ChannelFuture`s live in the private field
  `ServerConnectionListener.channels`, which has no accessor at all. The one accessor that does
  exist, `getConnections()`, returns connected *logins* — and `Connection` exposes a public
  `channel` field, so a less careful fix would have attached the multiplexer to a player's
  socket. Fixed by scanning fields, preferring channel-typed element types, and resolving
  `channel()` through the `ChannelFuture` interface.
- **`ChannelFuture.channel()` was unreachable by reflection.** Netty 4.2 declares
  `CompleteChannelFuture` and `SucceededChannelFuture` package-private, so a public method
  inherited from them cannot be invoked ordinarily — and the failure was being reported as
  "no `channel()` accessor", which is a lie. Fixed, and the real reason now reaches the trace.
- **The compiled pack differed between two identical servers.** `Map.copyOf` spreads entries by
  a per-JVM salt, so `BlockGeometry`'s slot map and `Cuboid`'s `EnumMap` of faces iterated
  differently in each process. Since the archive hash *is* the pack's identity, caches and the
  push protocol never agreed the pack was the same one. Both now preserve declaration order.
- **The particle texture was chosen at random.** It was "the first entry of the texture map",
  so a block broke into crystal particles on one server and base particles on another. It now
  comes from the topmost element's up face.
- **Eleven of the example's twelve faces named a texture that does not exist.**
  `.allFaces("base")` omits the `#` that makes a name a slot reference, so the client read it as
  the path `minecraft:base` and drew the missing-texture pattern — while every server-side check
  passed. There is now a test that walks the generated model and fails on any face that does not
  reference a declared slot.
- **`public-base-url` shipped hardcoded to port 25565**, silently breaking the pack URL on any
  server bound elsewhere. The shipped `config.yml` now leaves it unset so the port-aware default
  applies, and a mismatch is reported at startup instead of failing quietly.
