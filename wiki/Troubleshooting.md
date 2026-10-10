# Troubleshooting

Every entry here is a failure that actually happened on a real server, with what it looked like
and what it was.

## The injector never attached

**Symptom.** Server starts cleanly, `/uas status` shows `hooked: false`, and no pack is ever
delivered. The log ends with:

```
UAS could NOT attach to the Minecraft listener after 30 attempt(s). Asset streaming over the game port is unavailable. Trace:
walk root: CraftServer
server instance: DedicatedServer
connection listener: ServerConnectionListener
channels via getConnections(): SynchronizedRandomAccessList
the connection listener exposed no bound channels; tried fields [...] and accessors [...]
```

**Cause.** The reflective walk could not find the listening socket. On Paper 26.3 and Folia 26.2
the sockets are in the private field `ServerConnectionListener.channels`, which has **no
accessor at all** — while the one public method that looks right, `getConnections()`, returns
connected logins rather than channels.

**Fix.** Handled in `PaperChannelLocator` as of this revision: holders are found by field as
well as by accessor, and candidates whose element type names a `Channel`/`ChannelFuture` win.
If you still see this, the trace names the exact hop that failed — compare it against the walk
in [Architecture](Architecture.md#reaching-the-listening-socket).

**Note.** `shouldRetry` is true only when *no* channels were found at all. If the listener is
found but empty, that is a server that has not bound yet, and the retry loop is doing its job.

## A pack URL nobody can reach

**Symptom.** `UAS PACK URL:` names a port the server is not listening on. Players download
nothing and see vanilla textures; the log shows no error.

**Cause.** `public-base-url` was set to a fixed port. The bundled `config.yml` used to ship
`http://127.0.0.1:25565/uas/pack/` uncommented, which silently broke every server not on 25565.

**Fix.** Two changes. The shipped default is now commented out, so the URL is derived from the
port the server actually bound. And a mismatch is now reported:

```
WARN: UAS public-base-url 'http://127.0.0.1:25565/uas/pack/' names port 25565 but the server is listening on 25566; players would be handed an unreachable pack URL. Set public-base-url to port 25566, or to the public port if the server is behind a proxy.
```

**Remember.** The *host* genuinely cannot be discovered — behind NAT it is not the bind address.
Only the port is checked, and a URL with no port is never warned about.

## The block renders as the missing-texture pattern

**Symptom.** The block places and collides correctly, `/uas status` is healthy, the pack is
downloaded and applied — and the block is checkered magenta/black.

**Cause.** A face texture without a leading `#` is a concrete texture *path*, not a slot
reference. The example declared `.allFaces("base")` instead of `.allFaces("#base")`, so eleven
of its twelve faces were read by the client as `minecraft:base` — a texture that does not exist.

**Fix.** Faces must reference `#slot`, and the slot must be declared in the same model. There is
now a test that walks the generated model and fails if any face references an undeclared slot.

**Related.** A missing *file* looks identical in game. If the model is correct but the `.png`
was never shipped, the client shows the same pattern. That is what
`UAS pack expects asset: assets/uas/textures/block/crystal_lamp_crystal.png` is telling you.

## Two servers produce different packs for the same plugin

**Symptom.** The pack's SHA-1 is stable within one server process but differs between two
identical servers, or after a restart. Clients re-download the pack on every rotation; a CDN
never gets a cache hit.

**Cause.** `Map.copyOf` and the other immutable map factories spread their entries using a
per-JVM salt, so iteration order differs between processes. Anything derived by iterating a
texture-slot or face map — the Java model, a Bedrock block definition — emitted different bytes
for identical input. The particle texture was additionally chosen as "the first entry of the
texture map", which is arbitrary rather than merely unstable.

**Fix.** `BlockGeometry` and `Cuboid` now preserve declaration order (`Collections.unmodifiableMap`
over a `LinkedHashMap`/`EnumMap`) rather than normalising through `Map.copyOf`, and the particle
texture comes from the topmost element's up face.

**Rule of thumb.** If a value feeds a hash that identifies an artifact, its iteration order is
part of its contract. `Map.copyOf` is fine for a lookup table and wrong for anything serialised.

## `channel()` exists but reflection says otherwise

**Symptom.** A nullable walk over Netty futures reports *no `channel()` accessor* for a class
that plainly has one.

**Cause.** Netty 4.2 declares `CompleteChannelFuture` and `SucceededChannelFuture`
**package-private**. A public method inherited from a package-private class is not invocable by
ordinary reflection:

```
IllegalAccessException: class PaperChannelLocator cannot access a member of class io.netty.channel.CompleteChannelFuture with modifiers "public"
```

**Fix.** A `ChannelFuture` is unwrapped through the **interface** (`ChannelFuture.channel()`),
and `invoke` forces accessibility explicitly instead of inferring it from the method's own
modifiers. If a lookup fails, the reason now appears in the trace instead of being reported as
"no accessor".

## `/uas status` says `serverBound: false`

**Not a fault.** The engine never binds its own listener on a real server; it attaches to the
server's socket. `serverBound: false` next to `portless: true` and `hooked: true` is the
expected shape. `engine.port: 0` for the same reason.

## Folia refuses to load the plugin

**Cause.** Folia requires `folia-supported: true` in `plugin.yml` before it will load a plugin
at all, regardless of how carefully the code behaves. The flag is in the bundled `plugin.yml`;
if you build your own plugin from the library modules, you must add it yourself.

## The pack compiles but clients still see vanilla textures

Check, in this order:

1. `hooked: true` in `/uas status` — otherwise nothing is served (first entry above).
2. `curl -I <the PACK URL>` from *outside* the host — the classic problem is a loopback URL left
   in place on a public server. The host in `public-base-url` cannot be validated for you.
3. `pack-required` — left `false` by default, so a client that misses the prompt degrades to
   vanilla rather than being blocked. If you want a hard requirement, set it explicitly.
4. The `UAS pack expects asset:` lines — a structurally valid model pointing at files nobody
   shipped fails identically to a broken model.
