# p2p-something

A peer-to-peer file synchronization system built from scratch as a Scala learning project.

## Current state

The implementation is a **one-way file-transfer prototype**, not yet a peer-to-peer synchronizer.
One sender watches a local directory and sends file changes over plaintext TCP to one receiver.
The receiver writes those files into its own directory.

The automated end-to-end test confirms one top-level ASCII file transfer. The full test suite
passes, but the command-line demo has not been verified manually.

Implemented:

- top-level file create and modify events;
- one persistent sender-to-receiver TCP connection;
- a length-prefixed frame containing relative path, modification time, and file bytes;
- receiver-side directory creation, file writing, and modification-time restoration.

Not implemented:

- bidirectional synchronization or peer symmetry;
- startup scanning, nested-directory watching, delete, or rename propagation;
- conflict detection, version metadata, convergence, or persistence;
- reconnection, multiple peers, discovery, authentication, or encryption;
- validation of incoming paths and frame sizes.

Known implementation issues:

- The `send` CLI branch reads `receive.dir` and `receive.port` instead of its own options.
- `Sender` catches every exception, drops its cause, and logs an empty success message.
- Both sides use blocking loops without an explicit shutdown lifecycle.
- Each transfer reads the complete file into memory and can observe a file while it is still changing.
- The integration test waits five seconds, leaves temporary files behind, and relies on daemon
  threads instead of shutting the processes down.

## How it works

```
┌──────────────┐   watch dir   ┌──────────┐   TCP    ┌────────────┐   write   ┌──────────────┐
│  source dir  │ ────────────▶ │  Sender  │ ───────▶ │  Receiver  │ ────────▶ │  target dir  │
└──────────────┘   (NIO        └──────────┘  Frame   └────────────┘           └──────────────┘
                    WatchService)
```

- **`Sender`** uses `java.nio.file.WatchService` for top-level create and modify events.
- **`Receiver`** accepts one connection and writes each received frame under its target directory.
- **`Protocol`** encodes `Frame(path, mtime, bytes)` with `DataOutputStream`.
- **`util.Watcher`** is an unused recursive-watcher experiment.

## Usage

Start the receiver first (it listens on a port), then point a sender at it.

Create both directories before starting the processes. The current CLI option bug means the sender
commands work only because both subcommands define `--dir` and `--port` with matching types.

```bash
# Terminal 1 — listen and write incoming files into ./dirB
sbt 'run receive --dir dirB --port 9000'

# Terminal 2 — watch ./dirA and send changes to the receiver
sbt 'run send --dir dirA --host localhost --port 9000'

# Terminal 3 — make a change in the watched directory
echo "hello" > dirA/test.txt
# -> dirA/test.txt and dirB/test.txt now have identical contents
```

| Subcommand | Flags | Purpose |
|---|---|---|
| `receive` | `--dir`, `--port` | Listen on `port`, write received files under `dir`. |
| `send` | `--dir`, `--host`, `--port` | Watch `dir`, connect to `host:port`, stream changes. |

## Development

```bash
sbt compile        # compile
sbt test           # run all tests
sbt console        # Scala 3 REPL

# Run a single test suite or test:
sbt 'testOnly MySuite'
sbt 'testOnly MySuite -- --tests=example'   # munit name filter
```

## Tech

- **Scala 3.8.4**, sbt.
- Dependencies ([`build.sbt`](build.sbt)): Scallop, scala-logging with Logback,
  directory-watcher-better-files, cats-effect, fs2-core, MUnit, and munit-cats-effect.
- The production code does not yet use directory-watcher-better-files, cats-effect, or fs2.
- Sources under `src/main/scala`, tests under `src/test/scala`.

## Design constraints

These constraints remain intentional as the project grows:

- Use CRDT convergence rather than consensus algorithms or quorums.
- Use a vetted Noise implementation instead of custom cryptography.
- Verify CRDT merge commutativity, associativity, and idempotence with property tests.
- Keep conflict resolution pure and isolate IO in a thin shell.
- Keep the stack on Scala 3 and cats-effect.
