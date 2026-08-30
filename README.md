# p2p-something

A peer-to-peer file synchronization system built from scratch as a Scala learning project.

## Vision

The destination is a private tool for synchronizing a chosen folder with a friend or with another
machine such as an EC2 instance. Each machine is a peer: it can originate changes, reconnect after
being offline, exchange what the other side missed, and eventually reach the same folder state.

Peers should authenticate each other and encrypt file contents in transit. They should connect
directly when possible; an always-available EC2 peer or a relay can help when two personal machines
cannot reach each other. The system should handle concurrent edits and deletes predictably without
depending on a central coordinator.

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

## Active milestone: trustworthy one-way LAN transfer

This is the only active milestone. Run the sender and receiver on two machines on the same trusted
local network, then transfer top-level file creates and modifications safely and repeatably.

Implement it in this order:

1. Fix the `send` CLI branch so it reads `send.dir`, `send.host`, and `send.port`. Replace the empty
   transfer log and preserve exception causes.
2. Add pure validation at the network boundary. A path resolver should accept a sync root and wire
   path, then either return a confined target or a descriptive error. The protocol reader should
   reject negative and over-limit content lengths before allocating memory.
3. Give the sender and receiver an explicit shutdown lifecycle so tests can stop and join them.
4. Replace the fixed test sleep with bounded polling or a completion signal. Clean temporary
   directories and cover create, modify, zero-byte content, and a non-ASCII filename.
5. Run the CLI on two machines using the receiver's LAN IP and record any firewall or binding issue
   that appears.

The milestone is complete when all automated cases pass without leaked resources and the manual LAN
demo transfers the expected bytes. Unsafe paths and invalid frame sizes must fail without writing a
file. Recursive watching, deletes, renames, bidirectional sync, discovery, NAT traversal, and
encryption remain outside this milestone.

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
