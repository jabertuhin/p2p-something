# Sender — Implementation Plan (Phase 1)

> Scope: finish `Sender.scala`, the `send` side of the toy unidirectional sync. Pairs with `05-phase1-progress.md` (next step #3), `06-receiver-plan.md`, and `Protocol.scala` (done). Single-threaded, blocking, one peer — cats-effect deferred to Phase 2.

## Responsibility

Watch a directory for file create/modify events and, for each event, read the file and send it to the receiver as one length-prefixed `Frame`.

```
WatchService on dirA ──> Socket(host, port) ──> on each create/modify event, send a Frame
```

## CLI wiring

`SendConf` carries `dir`, `host`, `port` (host is required — the sender *dials* the receiver, so it needs the destination address; the receiver only binds a port and accepts). `Main` dispatches:
```scala
case Some(conf.send) => Sender.run(conf.send.dir(), conf.send.host(), conf.send.port())
```

## Full example

```scala
import com.typesafe.scalalogging.StrictLogging

import java.io.{BufferedOutputStream, DataOutputStream}
import java.net.Socket
import java.nio.file.{FileSystems, Files, Path, Paths}
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_MODIFY}
import scala.jdk.CollectionConverters.*

object Sender extends StrictLogging:
  def run(dir: String, host: String, port: Int): Unit =
    val dirA    = Paths.get(dir)
    val watcher = FileSystems.getDefault.newWatchService()
    dirA.register(watcher, ENTRY_CREATE, ENTRY_MODIFY)

    val socket = new Socket(host, port)
    val out    = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream))
    logger.info(s"watching $dirA, sending to $host:$port")

    try
      while true do
        val key = watcher.take()                       // BLOCKS until events arrive
        for ev <- key.pollEvents().asScala do
          val rel  = ev.context().asInstanceOf[Path]   // filename relative to dirA
          val full = dirA.resolve(rel)
          if Files.isRegularFile(full) then
            val bytes = Files.readAllBytes(full)
            val mtime = Files.getLastModifiedTime(full).toMillis
            Protocol.write(out, Frame(rel.toString, mtime, bytes))
            logger.info(s"sent $rel (${bytes.length} bytes)")
        key.reset()                                    // REQUIRED, or events stop
    finally
      socket.close()
      watcher.close()
```

## Per-event body — step by step

For each `ev` from `key.pollEvents()`:

1. `ev.context().asInstanceOf[Path]` — the filename relative to `dirA` (NOT an absolute path).
2. `dirA.resolve(rel)` — the absolute path to read from.
3. `Files.isRegularFile(full)` — skip directories and temp files that already vanished.
4. `Files.readAllBytes(full)` + `Files.getLastModifiedTime(full).toMillis`.
5. `Protocol.write(out, Frame(rel.toString, mtime, bytes))` — reuses the serializer from `Protocol.scala`; the receiver resolves `rel` under its own dir.

## Key decisions / gotchas

- **`key.reset()` is mandatory** every iteration. Forget it and the watch silently stops delivering events after the first batch.
- **`watcher.take()` blocks**; the loop exits only when the process is killed. Phase 1 has no graceful sender shutdown — that's fine.
- **macOS latency**: `WatchService` uses a polling backend on macOS → expect multi-second delay before events fire. Not a bug (decision #2 in `05-phase1-progress.md`).
- **One `echo` may fire CREATE then MODIFY** → the same file is sent twice. Harmless: the receiver overwrites. Real debounce is Phase 2.
- **Reading a file mid-write** can send partial content. Toy-acceptable.
- **`WatchService` is not recursive** — watches only the top level of `dirA`. Flat dir is fine for Phase 1.
- **Atomic-write editors** (vim/VS Code) do temp-file + rename → show up as CREATE of a temp name, not MODIFY of the target. Use `echo`/`printf`/`cat >>` to test.

## Definition of Done

Per `05-phase1-progress.md`:
```bash
mkdir -p /tmp/dirA /tmp/dirB
sbt 'run receive --dir /tmp/dirB --port 9999'                 # terminal 1
sbt 'run send --dir /tmp/dirA --host localhost --port 9999'   # terminal 2
echo "hello" > /tmp/dirA/test.txt                             # terminal 3 (wait a few s on macOS)
diff /tmp/dirA/test.txt /tmp/dirB/test.txt                    # silent == success
echo "again" >> /tmp/dirA/test.txt                            # modify also syncs
```
Done when: `diff` is silent after both a create and a modify, and the receiver exits cleanly when the sender disconnects.