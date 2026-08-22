# p2p-something

A peer-to-peer file synchronization tool, built **from scratch as a learning project** — a tiny Syncthing. The goal is to implement every layer by hand (CRDTs, vector clocks, SWIM gossip, NAT traversal, Noise-encrypted transport) rather than reuse off-the-shelf sync libraries.

See [`docs/`](docs/) for the full design: [overview](docs/01-overview.md), the [10-phase plan](docs/02-plan.md), [resources](docs/03-resources.md), and [implementation notes](docs/04-implementation-notes.md). Phase 2 is being built in [`docs/phase-2/`](docs/phase-2/).

## Status

**Phase 1 — toy sync, local, unidirectional. Written, not verified.**

One process watches a directory and streams changed files over plaintext TCP to another process, which writes them to its own directory. Handles **create** and **modify**; delete and rename are deferred to Phase 2. Sync is one-way only (sender → receiver) for now.

> ⚠️ **The demo below does not currently run.** The CLI's send branch reads the *receive* subcommand's options, so it fails at startup. Watching is also top-level only — files in subdirectories are never picked up. Both are fixed in [chunk 0](docs/phase-2/chunk-0-close-phase-1.md) of Phase 2.

Everything else from the roadmap — bidirectional sync, vector clocks, the CRDT merge, persistence, gossip discovery, encryption, NAT traversal — is still ahead. See [`docs/02-plan.md`](docs/02-plan.md) for what comes next.

## How it works

```
┌──────────────┐   watch dir   ┌──────────┐   TCP    ┌────────────┐   write   ┌──────────────┐
│  source dir  │ ────────────▶ │  Sender  │ ───────▶ │  Receiver  │ ────────▶ │  target dir  │
└──────────────┘   (NIO        └──────────┘  Frame   └────────────┘           └──────────────┘
                    WatchService)
```

- **`Sender`** registers a `java.nio.file.WatchService` on the source directory for `ENTRY_CREATE` / `ENTRY_MODIFY` events. On each event it reads the file's bytes and last-modified time and pushes a frame down the socket.
- **`Receiver`** runs a `ServerSocket`, accepts one connection, and for each incoming frame writes the bytes to the matching relative path (creating parent directories) and restores the modification time.
- **`Protocol`** is the wire format: a `Frame(path, mtime, bytes)` serialized with `DataOutputStream` as `UTF path · long mtime · int length · raw bytes`.

## Usage

Start the receiver first (it listens on a port), then point a sender at it.

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
- Dependencies ([`build.sbt`](build.sbt)): [scallop](https://github.com/scallop/scallop) (CLI parsing), [scala-logging](https://github.com/lightbend-labs/scala-logging) + logback (logging), [directory-watcher](https://github.com/gmethvin/directory-watcher) (native FSEvents on macOS, recursive watch), [cats-effect](https://typelevel.org/cats-effect/) + [fs2](https://fs2.io/) (Phase 2 concurrency), [munit](https://scalameta.org/munit/) + munit-cats-effect (test).
- Sources under `src/main/scala`, tests under `src/test/scala`.

## Design constraints

These are intentional and shouldn't be violated as the project grows:

- **No consensus algorithms.** No Raft, no Paxos, no quorums — convergence comes from CRDT math.
- **Don't roll your own crypto.** Phase 8 uses a vetted Noise library.
- **Property-based testing is mandatory for the CRDT merge** (Phase 4): the merge must be proven commutative, associative, and idempotent.
- Stack is fixed: **Scala 3 + (cats-effect or Pekko)**.