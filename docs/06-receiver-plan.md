# Receiver — Implementation Plan (Phase 1)

> Scope: finish `Receiver.scala`, the `receive` side of the toy unidirectional sync. Pairs with `05-phase1-progress.md` (next step #2) and `Protocol.scala` (already done). Single-threaded, blocking, one peer — cats-effect deferred to Phase 2.

## Responsibility

Listen on a TCP port, accept **one** peer, read length-prefixed frames in a loop, and write each frame's bytes to the receive directory. Exit cleanly when the sender disconnects.

```
new ServerSocket(port) ──> accept() one peer ──> loop Protocol.read ──> write each frame to disk
```

## CLI wiring

`Main.scala` dispatches:
```scala
case Some(conf.receive) => Receiver.run(conf.receive.dir(), conf.receive.port())
```
Currently `Main` prints `"receive"` — swap that for the call above once `run` is complete.

## Current state (as of this plan)

`Receiver.scala` exists with the socket/accept/read-loop skeleton, but three things are missing or wrong:

1. **No disk write.** The loop reads a `Frame` and discards it — nothing lands in the directory.
2. **No `finally` cleanup.** `socket` and `server` are never closed; sockets leak on exit.
3. **`catch { case _ => ... }` is too broad.** It treats a normal disconnect (`EOFException`) as an error, and also swallows fatal JVM errors (`case _` catches `Throwable`). The clean-disconnect path should be distinct and quiet.

## Target shape

```scala
import com.typesafe.scalalogging.StrictLogging
import java.io.{BufferedInputStream, DataInputStream, EOFException}
import java.net.ServerSocket
import java.nio.file.{Files, Paths}
import java.nio.file.attribute.FileTime

object Receiver extends StrictLogging:
  def run(dir: String, port: Int): Unit =
    val dirB   = Paths.get(dir)
    val server = new ServerSocket(port)
    logger.info(s"listening on $port, writing to $dirB")

    val socket = server.accept()                    // BLOCKS until sender connects
    val in = new DataInputStream(new BufferedInputStream(socket.getInputStream))

    try
      while true do
        val frame  = Protocol.read(in)              // BLOCKS until a full frame arrives
        val target = dirB.resolve(frame.path)
        Files.createDirectories(target.getParent)
        Files.write(target, frame.bytes)
        Files.setLastModifiedTime(target, FileTime.fromMillis(frame.mtime))
        logger.info(s"wrote ${frame.path} (${frame.bytes.length} bytes)")
    catch
      case _: EOFException => logger.info("peer disconnected, shutting down")
    finally
      socket.close()
      server.close()
```

## Per-frame body — step by step

For each `frame` returned by `Protocol.read`:

1. `dirB.resolve(frame.path)` — join the relative path onto the receive dir.
2. `Files.createDirectories(target.getParent)` — make parent dirs; no-op if they already exist.
3. `Files.write(target, frame.bytes)` — create or overwrite the file.
4. `Files.setLastModifiedTime(target, FileTime.fromMillis(frame.mtime))` — preserve mtime; sets up Phase 2 change-detection.
5. Log the path + size.

## Key decisions / gotchas

- **`while true` is not an infinite hang.** It exits via `EOFException`, which `Protocol.read` throws the instant the sender closes the socket. Catching *that specific exception* is the normal, clean shutdown path — log info, not error. This is the Definition of Done ("receiver exits cleanly, no stack trace, on disconnect").
- **Catch `EOFException`, not `_`.** A bare `case _` catches `Throwable` (incl. `OutOfMemoryError`) and would mask real bugs as a quiet "done." Let anything that isn't `EOFException` propagate.
- **`finally` always runs** — sockets close on both the clean path and any exception. Without it, the port can stay bound after exit.
- **Blocking is fine here.** `accept()` and `read` both block; Phase 1 is one peer, one thread by design.
- **`BufferedInputStream`** wraps the raw socket stream so reads aren't one syscall per byte. Keep it.
- **Path traversal** (`frame.path` containing `..`) is ignored — toy-acceptable for Phase 1, revisit when peers are untrusted.

## Definition of Done

Per `05-phase1-progress.md`:
```bash
mkdir -p /tmp/dirA /tmp/dirB
sbt 'run receive --dir /tmp/dirB --port 9999'                 # this process
sbt 'run send --dir /tmp/dirA --host localhost --port 9999'   # the sender (next file)
echo "hello" > /tmp/dirA/test.txt
diff /tmp/dirA/test.txt /tmp/dirB/test.txt                    # silent == success
```
Done when: the file appears under `/tmp/dirB`, `diff` is silent, and the receiver logs "peer disconnected" (no stack trace) when the sender stops.