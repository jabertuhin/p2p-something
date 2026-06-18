# Phase 1 — Progress & Handoff

> Working doc. Update the **Status** and **Next steps** as you go. This captures decisions and state so any future session (or you, after a break) can continue without re-deriving anything. Phase definitions live in `02-plan.md`; cross-cutting decisions in `04-implementation-notes.md`.

## Status (as of this handoff)

**Phase 1 — toy sync, local, unidirectional. In progress.**

- [x] CLI scaffolding: scallop subcommands `send` / `receive` in `Main.scala`.
- [ ] `Protocol.scala` — wire format (designed, not written).
- [ ] `Receiver.scala` — TCP listen + write files.
- [ ] `Sender.scala` — watch dir + send files.
- [ ] End-to-end verify (`echo` in dirA → file appears in dirB).

## Decisions locked in

1. **Plain imperative Scala for Phase 1.** No cats-effect yet — adopt cats-effect + fs2 at Phase 2 where concurrency (loopback guard, bidirectional, debounce) justifies it. See `04-implementation-notes.md`.
2. **JDK `java.nio.file.WatchService` only — no `directory-watcher` dependency for Phase 1.** Purest raw-Scala, zero deps, learn the raw API. Trade-off accepted: on **macOS** `WatchService` uses a polling backend → expect **multi-second latency** before events fire (not a bug). `directory-watcher` remains the planned upgrade for later phases. (This reverses the original `04-implementation-notes.md` choice — that file should be updated to reflect Phase-1 = WatchService.)
3. **Working style:** author writes the code; assistant guides with APIs/snippets and reviews. Author is fluent in Scala (data-engineering background) but new to cats-effect.
4. **No new build dependencies.** `scallop` (already present) covers the CLI. `build.sbt` unchanged.

## Architecture

Single binary, two modes, two JVM processes on localhost, unidirectional:

- `receive` mode: `ServerSocket(port)` → `accept()` one peer → loop reading frames → write files under dir B.
- `send` mode: `WatchService` on dir A → `Socket(host, port)` → on each create/modify event, send a frame.

CLI:
- `receive --dir <pathB> --port <p>`
- `send --dir <pathA> --host <h> --port <p>`

### Planned files

| File | Responsibility | State |
|---|---|---|
| `Main.scala` | Parse CLI, dispatch to send/receive. | Done |
| `Protocol.scala` | `Frame(path, mtime, bytes)` + `write`/`read`. | TODO |
| `Receiver.scala` | Listen, accept one peer, read loop, write files. | TODO |
| `Sender.scala` | Watch dir A, connect, send a frame per event. | TODO |

## Gotcha already hit (and the lesson)

**Type ascription on an anonymous-class `val` hides its members.** Writing `val receive: Subcommand = new Subcommand("receive"): ...` upcast the val to `Subcommand`, so `conf.receive.dir` became invisible (`dir`/`port` are members of the anonymous subclass, not of `Subcommand`). Fix: drop the `: Subcommand` ascription and let Scala 3 infer the refinement type. General rule: an explicit supertype annotation on a val holding an anonymous class throws away the synthetic refinement.

Correct shape:
```scala
val receive = new Subcommand("receive"):
  val dir  = opt[String](required = true)
  val port = opt[Int](required = true)
addSubcommand(receive)        // required — defining the val alone doesn't register it
```
Dispatch matches by stable identifier (same object), not by name:
```scala
conf.subcommand match
  case Some(conf.receive) => ...
  case Some(conf.send)    => ...
  case _                  => conf.printHelp()
```

## Next steps (in order)

### 1. `Protocol.scala`
Single source of truth for the byte layout. Use `DataInputStream`/`DataOutputStream` for free length-prefixed primitives.
```scala
case class Frame(path: String, mtime: Long, bytes: Array[Byte])

object Protocol:
  def write(out: DataOutputStream, f: Frame): Unit =
    out.writeUTF(f.path); out.writeLong(f.mtime)
    out.writeInt(f.bytes.length); out.write(f.bytes); out.flush()

  def read(in: DataInputStream): Frame =
    val path = in.readUTF(); val mtime = in.readLong()
    val n = in.readInt(); val bytes = new Array[Byte](n)
    in.readFully(bytes)                 // NOT read() — handles partial reads
    Frame(path, mtime, bytes)
```
Why: TCP has no message boundaries → length-prefix each field so the reader knows where each frame ends. `readFully` blocks until all `n` bytes arrive.

### 2. `Receiver.scala`
- `new ServerSocket(port)`; `accept()` (blocks for one peer).
- Wrap `sock.getInputStream` in `DataInputStream`; loop `Protocol.read` until `EOFException` (catch it = clean disconnect, no stack-trace spew).
- Per frame: `val target = dirB.resolve(relPath)`; `Files.createDirectories(target.getParent)`; `Files.write(target, bytes)`; optionally `Files.setLastModifiedTime(target, FileTime.fromMillis(mtime))` (sets up Phase 2). Log each path.

### 3. `Sender.scala`
- `FileSystems.getDefault.newWatchService()`; `dirA.register(watcher, ENTRY_CREATE, ENTRY_MODIFY)`.
- `new Socket(host, port)`; wrap output in `DataOutputStream`.
- Loop: `val key = watcher.take()`; `for ev <- key.pollEvents().asScala` (`import scala.jdk.CollectionConverters.*`):
  - `ev.context()` is the filename relative to the watched dir.
  - `dirA.resolve(...)`, skip non-regular files (`Files.isRegularFile`).
  - `Files.readAllBytes`, `Files.getLastModifiedTime(...).toMillis`, send `Frame`.
  - `key.reset()` each iteration (required, or events stop).

### 4. Update `04-implementation-notes.md`
Record that Phase 1 uses `WatchService` (not `directory-watcher`), with the macOS-latency trade-off.

## Known rough edges — note, don't fix this phase

- macOS event latency (polling backend) — expect seconds.
- One `echo` may fire CREATE then MODIFY → frame sent twice (harmless; receiver overwrites). Real debounce = Phase 2.
- Atomic-write editors (vim/VS Code) do temp-file + rename → show up as CREATE of a temp name, not MODIFY of target. `echo`/`printf`/`cat >>` behave as expected.
- Reading a file mid-write can send partial content. Toy-acceptable.
- `WatchService` is not recursive — watches only the top level of dir A. Flat dir is fine for Phase 1.

## Verification (Definition of Done)

```bash
mkdir -p /tmp/dirA /tmp/dirB
sbt 'run receive --dir /tmp/dirB --port 9999'           # terminal 1
sbt 'run send --dir /tmp/dirA --host localhost --port 9999'  # terminal 2
echo "hello" > /tmp/dirA/test.txt                       # terminal 3 (wait a few s on macOS)
diff /tmp/dirA/test.txt /tmp/dirB/test.txt              # silent == success
echo "again" >> /tmp/dirA/test.txt                      # modify also syncs
```
Done when: `diff` is silent after both a create and a modify, and the receiver exits cleanly (no stack trace) when the sender disconnects.