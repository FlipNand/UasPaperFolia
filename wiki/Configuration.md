# Configuration

`plugins/UASExample/config.yml`. The defaults are chosen for the constrained case: an
operator who changes nothing gets memory-safe behaviour and a working pack URL.

## The one key that matters

```yaml
# public-base-url: "http://127.0.0.1:25565/uas/pack/"
```

**Left commented out on purpose.** When it is unset, the plugin derives the URL from the port
the server actually bound, which is the part it can know. Set it when the *host* a client dials
is not the host the server binds — behind NAT, a proxy, a container, or a CDN:

```yaml
public-base-url: "https://cdn.example.com/uas/pack/"
```

Two things to know:

- **The port is checked.** If the URL names a port the server is not listening on, startup logs
  a warning naming both numbers. Without that check the failure is invisible: the pack
  compiles, registers and serves, and every client is simply handed a URL nothing answers.
- **A URL with no port is never warned about.** `https://cdn.example.com/uas/pack/` is a normal
  configuration, not a mistake.

## Every key

| Key | Default | Meaning |
| --- | --- | --- |
| `low-memory-mode` | `false` | Force the constrained memory profile regardless of heap size. Leave false and it is still auto-detected from the heap the engine actually has |
| `public-base-url` | unset | Absolute URL clients fetch the pack from, including the play port. Derived from the bound port when unset |
| `pack-prompt` | `UAS example resource pack` | The text a client shows in the pack-prompt dialog |
| `pack-required` | `false` | Whether the pack must be applied to play. Left false so a failed download degrades to vanilla textures instead of locking players out |
| `verify-packs-on-register` | `false` | Re-hash an archive at registration. Only worth it for hand-copied packs; the compiler already hashed while writing |
| `max-tracked-players` | `4096` | Ceiling on per-player state |
| `hook-retry-interval-ticks` | `10` | Delay between injection attempts |
| `max-hook-attempts` | `30` | Attempts before giving up and logging the trace at SEVERE |
| `pack-sweep-interval-ticks` | `200` | How often each player's pack generation is compared and re-pushed. One counter comparison per player |
| `ownership-guard` | `true` | Log SEVERE when a block is mutated off its owning region thread. A development guard — on Folia such a mutation corrupts world state rather than throwing, so the check has to be the engine's own |
| `log-startup-trace` | `true` | Print the full status report and the injection trace at startup |

## Memory profiles

The profile is chosen from `Runtime.maxMemory()`, not from the machine's RAM, so a 4 GiB
instance gets the constrained profile without being told. With `-Xmx2G` the engine reports:

```
engine.profile: low_ram
engine.constrained: true
engine.budget.maxHeapBytes: 2147483648
engine.budget.maxHeapMiB: 2048
engine.budget.compileHeapBudgetBytes: 67108864        # 64 MiB
engine.budget.httpHeapBudgetBytes: 214748364         # 205 MiB
engine.budget.directMemoryBudgetBytes: 214748364     # 205 MiB
engine.budget.maxAssetBytesReadIntoMemory: 524288    # 512 KiB
engine.budget.cachesEnabled: false
engine.budget.copyBufferBytes: 16384
```

`cachesEnabled: false` and a 512 KiB per-asset ceiling are what keep a pack compile from
needing a whole-heap buffer. The example's compile peaks at about a fifth of a 2 GiB heap.

## Pack URL shape

Packs are served under `/uas/pack/<pack-id>`. `uas-example` is one id; the path prefix is
shared with `UasHttpConfig.DEFAULT_PATH_PREFIX`, so changing it in one place without the other
is a bug the engine would not catch for you.

## Rotation

Publishing a new pack advances a generation counter. Every player's generation is compared on
the sweep interval and only players who are behind are pushed, so a pack rotation does not
round-trip every player through a re-download. The generation also appears in `/uas packs`.
