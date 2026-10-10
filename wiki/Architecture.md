# Architecture

## Portless serving

A server that hosts custom content has to get a resource pack to each client. The usual
answers are a second HTTP port (another firewall rule, another thing to monitor, another
port to collide) or an external host (an upload step that can silently fall out of date).
UAS does neither: it attaches a multiplexer to the socket the server already owns.

```
                     ┌──────────────────────────────────────────┐
   client connects → │ server's listening Channel (port 25565)  │
                     │   └── ProtocolMultiplexer (first handler) │
                     └───────────────┬──────────────────────────┘
                       sniff first bytes
                  ┌──────────────────┴───────────────────┐
        looks like HTTP                          looks like Minecraft
                  │                                      │
        PackHttpRouter → registry              original pipeline, untouched
        GET /uas/pack/<id> → zip               (login, compression, encryption…)
```

The sniffing happens once per connection, on the first bytes. After it decides, the handler
removes itself and the connection is a plain HTTP or plain Minecraft connection for the rest
of its life — there is no per-packet overhead and no re-classification later.

`/uas status` reports the outcome:

```
sniff.connections: 8
sniff.minecraftConnections: 1
sniff.httpConnections: 7
sniff.ambiguousBytes: 0
sniff.timeoutClosed: 0
```

`ambiguousBytes: 0` is the number to watch. A non-zero value means traffic arrived that the
sniffer could not classify, which is the only way a client ends up on the wrong branch.

## Reaching the listening socket

The socket is private server state with no Bukkit accessor, so it is reached by an ordered
reflective walk:

```
CraftServer → MinecraftServer → ServerConnectionListener → channels → ChannelFuture.channel()
```

Two details of that walk are load-bearing and were both discovered the hard way on a real
server:

1. **`channels` is a field, not an accessor.** On modern Paper the listening `ChannelFuture`s
   live in a private field with no getter of any kind. A walk that only tries method names
   cannot see the socket, no matter how many names it tries.
2. **`getConnections()` is a decoy.** It exists, it is public, and on modern Paper it returns
   `List<net.minecraft.network.Connection>` — connected *logins*, not listening channels. Worse,
   `Connection` exposes a public `channel` field, so a walk that unwrapped it would attach the
   asset multiplexer to a *player's* socket.

So the locator prefers candidates whose declared element type names a `Channel` or
`ChannelFuture`, and resolves `channel()` through the `ChannelFuture` **interface** rather than
the runtime class. That last point matters more than it looks: Netty 4.2 declares
`CompleteChannelFuture` and `SucceededChannelFuture` package-private, so a "public" method
inherited from them is not invocable by ordinary reflection at all.

Every hop appends a trace line. A failed walk logs which step broke and what it saw instead,
because the alternative — attaching nothing and saying nothing — means the operator only finds
out when a player reports default textures.

## Injection is retried, not assumed

A plugin's `onEnable` can run before the server has bound, and Paper installs its acceptor from
a task posted to the event loop. The first attempt is made immediately; if no bound channel is
found it is retried on the global thread up to `max-hook-attempts` times. Re-running is safe:
an already-hooked channel counts as hooked, so a retry after a partial failure cannot stack two
interceptors on one socket. `/uas inject` runs the same operation on demand.

## The platform seam

`PaperUasPlatform` is the only file where the platform is allowed to matter. Everything else is
platform-free, so "what does this engine do differently on Folia?" has one honest answer. It is
visible in `/uas status`:

```
Paper:  platform.regionised: false
        scheduler.strategy: per-player work is serialised onto the single main thread;
                            concurrency is limited to async work that touches no world state

Folia:  platform.regionised: true
        scheduler.strategy: per-player work is dispatched to the owning region thread;
                            N players in N regions are served by N threads in parallel
```

Folia refuses to load a plugin that does not declare `folia-supported: true`, regardless of how
carefully it behaves; the flag is the claim, and per-player work really is dispatched through
the owning region thread here. Mutating world state off the owning region thread corrupts it
rather than throwing, which is why `ownership-guard` defaults on.

## Memory

`MemoryProfile` is derived from `Runtime.maxMemory()`, so a modest heap gets the constrained
behaviour without being told. The pack compiler is bounded by `compileHeapBudgetBytes` and
streams entries rather than buffering the tree, which is what lets it run in the background of
a 2 GiB instance — see [Configuration](Configuration.md#memory-profiles).

## Reproducibility

Compiling the same declarations must produce the same bytes, because the archive hash *is* the
pack's identity: the ETag, the cache key and the push protocol all key off it. Two identical
servers that disagree about the hash hand the same pack to every client forever.

That is why the geometry records wrap their maps rather than normalising them through
`Map.copyOf`. The immutable map factories spread entries using a **per-JVM salt**, so iteration
order changes between processes; anything derived by iterating a slot or face map would then
emit different bytes for identical input. See
[Verification](Verification.md#the-pack-is-now-byte-identical-across-servers).
