# UAS — Unified Asset Streaming

UAS serves custom blocks, sounds, HUDs and cross-platform packets to Java **and** Bedrock
clients **over the port Minecraft already uses**. There is no second listener, no separate
web server and no extra firewall rule: the engine attaches a protocol multiplexer to the
server's own listening socket, sniffs each connection as it arrives, and routes HTTP
requests for resource-pack assets while passing Minecraft traffic through untouched.

## Where to start

| Page | What it covers |
| --- | --- |
| [Getting Started](Getting-Started.md) | Install the plugin, confirm it attached, fetch the pack |
| [Configuration](Configuration.md) | Every key in `config.yml`, and what the defaults do |
| [Architecture](Architecture.md) | How portless serving, collection and the platform seam work |
| [Verification](Verification.md) | The recorded run on real Paper and Folia, with real output |
| [Troubleshooting](Troubleshooting.md) | Every failure mode this project has actually hit |

## The 60-second version

```bash
./gradlew build                      # -> dist/uas-example-1.0.0.jar
```

Drop [`uas-example-1.0.0.jar`](../dist/uas-example-1.0.0.jar) into `plugins/`, start the
server, and look for two lines:

```
UAS attached the asset multiplexer to the Minecraft listener (1 of 1 listening channel(s) hooked); packs are served on the game port, no extra port is open
UAS PACK URL: http://127.0.0.1:25565/uas/pack/uas-example
```

If the first line is missing, the engine is running but no client can fetch anything — see
[Troubleshooting](Troubleshooting.md#the-injector-never-attached). Then check it from a shell:

```bash
curl -I http://127.0.0.1:25565/uas/pack/uas-example   # -> 200, content-type: application/zip
```

That URL is the game port. Serving it is the whole point of the project.

## Modules

| Module | Role | Deployable? |
| --- | --- | --- |
| `uas-api` | Declarations: blocks, geometry, sounds, UI schemas. No platform types | library |
| `uas-core` | Engine: registry, pack compiler, memory budgets, UI manager | library |
| `uas-netty` | Portless HTTP, protocol sniffing, the pipeline multiplexer | library |
| `uas-protocol` | Binary framing and the standard packet set | library |
| `uas-paper` | The Paper/Folia seam: injection, scheduler, pack pusher | library |
| `uas-geyser` | Geyser extension plus JIT Java→Bedrock translation | Geyser extension |
| `uas-example` | The worked example, shaded into one installable plugin | **plugin jar** |

Only `uas-example` is a drop-in plugin. The rest are on the compile classpath; the example
jar shades them, which is why it is the one you install.

## Requirements

- **Paper 26.3+** or **Folia 26.2+** (both verified — see [Verification](Verification.md))
- **Java 25** for those builds; the plugin itself targets a lower release and loads on Java 21
- `folia-supported: true` is already set in `plugin.yml`; Folia refuses to load a plugin
  without it regardless of how carefully the code behaves
