# Getting Started

## 1. Build

```bash
./gradlew build            # compiles everything and runs the full test suite
```

The installable plugin is written to `dist/uas-example-1.0.0.jar`. The same artifact is at
`uas-example/build/libs/uas-example-1.0.0.jar` after every build; `dist/` merely collects it
next to the library jars and a `SHA256SUMS.txt`.

Verify what you are about to install:

```bash
cd dist && shasum -a 256 -c SHA256SUMS.txt
```

## 2. Install

```bash
mkdir -p plugins && cp dist/uas-example-1.0.0.jar plugins/
```

Start the server once so it writes a default config, then look at
`plugins/UASExample/config.yml`. Every default is the safe one; **you do not need to edit it
for a local test**.

## 3. Confirm the engine attached

On startup you should see this block:

```
[dev.uas.paper.PaperUasPlatform] UAS injection: walk root: CraftServer
[dev.uas.paper.PaperUasPlatform] UAS injection: server instance: DedicatedServer
[dev.uas.paper.PaperUasPlatform] UAS injection: connection listener: ServerConnectionListener
[dev.uas.paper.PaperUasPlatform] UAS injection: channels via field channels: SynchronizedRandomAccessList
[dev.uas.paper.PaperUasPlatform] UAS injection: located 1 listening channel(s)
[dev.uas.paper.PaperUasPlatform] UAS injection: hooked: KQueueServerSocketChannel[/[0:0:0:0:0:0:0:0]:25565]
[dev.uas.paper.PaperUasPlatform] UAS attached the asset multiplexer to the Minecraft listener (1 of 1 listening channel(s) hooked); packs are served on the game port, no extra port is open
```

The middle lines are a *trace*, not chatter: they name each hop of the reflective walk to the
server's private listening socket. When a future server build renames something, the failing
step is visible here instead of showing up later as players seeing default textures.

Then the pack is compiled off the main thread and published:

```
[UASExample] pack 'uas-example': 6 entries (0 stored), 0MiB uncompressed -> 0MiB archive, sha1=49624535ef0032b28e824e263bdbd625ae0df67d, peakHeap=367MiB of 2048MiB (18.0%), profile=low_ram
[UASExample] UAS PACK URL: http://127.0.0.1:25565/uas/pack/uas-example
```

## 4. Fetch the pack over the game port

```bash
curl -I  http://127.0.0.1:25565/uas/pack/uas-example
curl -o pack.zip http://127.0.0.1:25565/uas/pack/uas-example
```

Expected:

```
HTTP/1.1 200 OK
content-type: application/zip
etag: "49624535ef0032b28e824e263bdbd625ae0df67d"
cache-control: public, max-age=31536000, immutable
accept-ranges: bytes
```

The `etag` is the archive's own SHA-1, so a client or CDN can validate a cached pack without
asking the server again.

## 5. Prove the port is still a Minecraft port

The multiplexer is not a proxy that "mostly works". Confirm the game protocol survives on the
same socket:

```bash
# vanilla clients do this on every server list refresh
python3 - <<'PY'
import socket, struct, json
def varint(n):
    out=b""
    while True:
        b=n&0x7F; n>>=7
        out+=bytes([b|(0x80 if n else 0)])
        if not n: return out
def read_varint(s):
    num=shift=0
    while True:
        b=s.recv(1)[0]; num|=(b&0x7F)<<shift
        if not b&0x80: return num
        shift+=7
s=socket.create_connection(("127.0.0.1",25565),timeout=10)
h=b"127.0.0.1"
body=varint(0)+varint(769)+varint(len(h))+h+struct.pack(">H",25565)+varint(1)
s.sendall(varint(len(body))+body+varint(1)+varint(0))
read_varint(s); read_varint(s); n=read_varint(s)
print(json.loads(s.recv(n))["version"])
PY
```

You should see your server's own version, e.g. `{'name': 'Paper 26.3', 'protocol': 777}`.

## 6. Inspect from the console

```
/uas status      everything: platform, injection, engine, budgets, sniff counters
/uas packs       registered packs, URLs, push state
/uas blocks      block backends, state allocation, degraded geometry
/uas inject      re-attempt pipeline injection without restarting
```

`/uas status` is the one to read first:

```
  platform: Paper
  platform.regionised: false
  portless: true
  hooked: true
  hookAttempts: 1
  scheduler.strategy: per-player work is serialised onto the single main thread; ...
  engine.profile: low_ram
  engine.packs.packs: 1
  engine.packs.packIds: uas-example
  engine.serverBound: false
```

`portless: true` next to `engine.serverBound: false` is the expected shape, not a missing
step: the engine never binds its own listener on a real server, it attaches to the server's.
