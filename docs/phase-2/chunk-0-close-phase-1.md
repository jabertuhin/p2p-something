# Chunk 0 — Close Phase 1

**Status:** 🚧 in progress — claimed 2026-08-23. **1 of 10 done** as of 2026-08-29.

**Depends on:** nothing. **Unblocks:** chunk 2.

**Goal:** establish a reliable Phase 1 baseline before adding Phase 2 behavior.

## Checklist

### Fix and verify the current implementation

- [ ] Fix the send branch to read `conf.send.dir`, `conf.send.host`, and `conf.send.port`.
- [ ] Run the README demo in two terminals and confirm a file transfers byte-for-byte.
- [ ] Preserve and log `Sender` exceptions instead of swallowing them.
- [ ] Replace `logger.info("")` with a useful transfer message.

### Complete the transfer tests

- [x] Top-level ASCII file transfers with byte-identical content.
  - Implemented in `MainTestSuite` on 2026-08-29.
  - Uses `Receiver.run` readiness callbacks (`onListening` and `onConnected`) and port `0` to avoid
    startup races and port collisions.
- [ ] Zero-byte file arrives and remains zero bytes.
- [ ] A non-ASCII filename arrives intact.
- [ ] Modifying an existing file propagates the new content.

### Close the documentation and learning loop

- [ ] Update `README.md` if the architecture diagram, usage, or dependency list became stale.
- [ ] Answer the [question to sit with](#question-to-sit-with) in writing.

## Manual verification

Create `dirA/` and `dirB/`, then run:

```bash
# Terminal 1
sbt 'run receive --dir dirB --port 9000'

# Terminal 2
sbt 'run send --dir dirA --host localhost --port 9000'
```

Create or modify a top-level file in `dirA/`. Confirm the corresponding file in `dirB/` has the
same bytes.

## Test constraints

Use one integration suite with real sockets and temporary directories.

- Start the receiver before the sender.
- Use port `0` to avoid collisions.
- Wait using readiness signals and deadline-based polling, not fixed sleeps.
- Create and delete temporary directories per test.
- Mark background threads as daemons.
- Keep the suite to the four checklist cases above.

Before adding the remaining tests, clean up the existing test:

- Replace the five-second `Thread.sleep`.
- Delete temporary directories after each test.
- Bind `bound.get` once and reuse its value.
- Rename `test("Full test")` to describe its behavior.
- Remove the commented-out `threadA.start()`.

## Known limitations

- **Nested paths are out of scope.** `Sender` only watches the top-level directory. Recursive
  watching arrives in chunk 4 and is tested in chunk 6.
- **Tests cannot shut the sockets down cleanly.** `Receiver.run` owns its `ServerSocket` and returns
  no lifecycle handle. Port `0` and daemon threads keep this tolerable until chunk 3 introduces the
  transport boundary.
- **A change event can produce a partial read.** The watcher may fire while a large file is still
  being written. Content hashing addresses this later; do not solve it in this chunk.

## Why the CLI fix matters

The send branch currently reads the receive subcommand's `dir` and `port`. It happens to work
because both subcommands use options with the same names and types, so Scallop returns the shared
values. This is a latent bug: either subcommand can change later without a compile or runtime error
pointing to the wrong configuration read.

Make only the three field-read corrections here. Chunk 3 replaces both asymmetric subcommands, so
do not restructure the CLI in this chunk.

## Exit gate

The README command reliably transfers a file, all four integration tests pass, and the checklist is
complete.

## Question to sit with

The transfer test exercises the filesystem watcher and TCP framing together:

> Which assertions would still pass if the watcher were broken? Which would still pass if TCP
> framing were broken?

If every failure looks the same, the test has little diagnostic value. Chunks 2 and 3 introduce the
seams needed to test those subsystems separately.

## Reading

- [`DataInput.readFully` contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/DataInput.html#readFully(byte%5B%5D)>)
- [Phase resources](../03-resources.md) — see the *Hands-on Scala Programming* file-synchronizer
  chapter.
