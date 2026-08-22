# Chunk 0 — Close Phase 1

**Status:** 🚧 in progress — claimed 2026-08-23.
**Depends on:** nothing. **Unblocks:** chunk 2.
**Size:** an evening.

## Progress

- [ ] Fix the CLI send branch to read the send subcommand's own `dir`, `host`, `port`
- [ ] Run the README two-terminal demo by hand and confirm a file transfers
- [ ] Make `Sender`'s error handling report the exception instead of swallowing it
- [ ] Give `logger.info("")` something to say
- [ ] Write the transfer test: top-level ASCII file, byte-identical
- [ ] Test: zero-byte file arrives zero-byte
- [ ] Test: non-ASCII filename arrives intact
- [ ] Test: modifying an existing file propagates the new content
- [ ] Update `README.md`'s architecture diagram, usage section, and dependency list if this
      chunk's changes made them stale
- [ ] Answer the "Question to sit with" below, in writing

## Why this chunk exists

Phase 1's code exists and compiles. It has never been observed working. Those are unrelated facts,
and the gap between them is the whole point of this chunk.

The specific failure is worth sitting with. In `Main.scala`, the send branch reads
`conf.receive.dir()` and `conf.receive.port()` — the *receive* subcommand's options — while
dispatching to the sender. The compiler is perfectly happy. `ReceiveConf` and `SendConf` are
distinct classes, but the fields you're reading (`dir: ScallopOption[String]`,
`port: ScallopOption[Int]`) have identical types in both, so nothing about the expression is
ill-typed. It fails at runtime instead: scallop throws when you read an option belonging to a
subcommand that wasn't invoked.

**The lesson isn't "be careful."** It's that two types with the same shape are interchangeable to a
type checker, and that "the same shape" is a design choice you made. Phase 2 dissolves this bug
rather than fixing it — decision 6 replaces the two asymmetric subcommands with one symmetric
command, so there is no second config object to accidentally read from. Keep an eye out for this
pattern; it recurs constantly in configuration code.

The second thing this chunk establishes is a floor. From here on, when something breaks in Phase 2,
you can ask "did I break it?" and get an answer. Without that floor, every Phase 2 bug is
ambiguous — and Phase 2's bugs are subtle enough already.

## Spec

When this chunk is done, all of the following are true:

1. The documented two-terminal demo in `README.md` runs and transfers a file, using exactly the
   commands as written.
2. The send path reads the send subcommand's own directory, host, and port.
3. A file created in the watched directory appears at the same relative path in the target
   directory with byte-identical content.
4. A zero-byte file transfers as a zero-byte file.
5. A file whose name contains non-ASCII characters arrives with its name intact.
6. An automated test proves 3, 4, and 5 without a human watching two terminals.
7. `README.md`'s architecture diagram, usage section, and dependency list match reality.
   *(`CLAUDE.md` and `AGENTS.md` were already brought up to date on 2026-08-23; `README.md`'s status
   section and broken-demo warning too. What remains is whatever this chunk's code changes make
   stale.)*

**Explicitly not in scope for this chunk:** files in *subdirectories*. See the trap below.

## Implementation guidance

**The CLI fix** is three field reads. Don't restructure the CLI here — chunk 3 replaces it entirely
with the symmetric command, and reshaping it twice is wasted work.

**The test** is genuinely awkward to write, and that awkwardness is information. `Sender.run` and
`Receiver.run` are both infinite blocking loops with no return value and no shutdown signal. To test
them you have to start each on its own thread, poll the target directory until the file appears or a
timeout expires, and then abandon the threads. There is no clean way to stop them.

Write it anyway — the coverage is worth more than the ugliness costs. But notice *why* it's ugly:
there is no seam. The loop, the socket, the filesystem, and the lifetime are one inseparable blob.
Chunk 3 introduces a transport boundary and this same test becomes ordinary. That contrast is the
argument for seams, and it lands much harder if you've felt the bad version first.

Practical notes for the test:
- Use temporary directories, created and deleted per test.
- Bind the listener to port `0` if you can, or pick a high port per test; a hard-coded port makes
  the suite fail when run twice in quick succession or in parallel.
- Poll with a deadline rather than `Thread.sleep(2000)`. A sleep long enough to be reliable is long
  enough to be annoying, and a sleep short enough to be pleasant is flaky.
- Start the receiver before the sender. The sender's `new Socket(host, port)` fails immediately if
  nothing is listening.
- Mark the threads as daemons so a hung loop doesn't keep the JVM alive after the suite finishes.

**Documentation updates.** `README.md` describes the status accurately but the two guidance files do
not. While you're in there, `README.md`'s dependency list also predates the Phase 2 additions.

## Test criteria

One suite, at the crudest possible seam (whole process, real socket, real filesystem):

- Top-level file with ordinary ASCII content transfers with identical bytes.
- Zero-byte file transfers and arrives zero-byte, not absent.
- File named with non-ASCII characters arrives with the name intact.
- Modifying an existing file propagates the new content.

Four tests. Resist adding more here — the interesting cases belong at the seams that chunk 2 and
chunk 5 introduce, where they run in microseconds instead of seconds.

## Traps

**Nested paths will not work, and that's expected.** `Sender` registers `WatchService` on the top
level directory only. `WatchService` does not recurse, and it does not auto-register directories
created later. So a file written to `dirA/sub/file.txt` is never seen and never sent. Note that the
*receiver* handles nested paths fine — it calls `createDirectories` on the parent. The asymmetry is
worth noticing: the write side is correct and the watch side is not, which is exactly the kind of
half-implemented feature that looks working until someone makes a folder. Recursion arrives with
`RecursiveFileMonitor` in chunk 4; the nested-path case is asserted in chunk 6.

**`Sender`'s error handling hides everything.** `catch case _ => logger.error("Error has occurred in
sender side.")` catches every `Throwable`, discards it, and logs a message containing no
information. If your test fails mysteriously, this is why. Consider logging the exception while
you're here; you'll want it in the next few chunks.

**`logger.info("")` logs an empty line** on every frame sent. Harmless, but it's the only signal you
have that anything happened, so make it say something.

**Reading a file the instant it changes can give you a partial read.** The watcher fires when the
write *starts* being visible, not when it finishes. `Files.readAllBytes` on a large file
mid-write returns what exists at that moment. With `echo "hello" >` you'll never see this; with a
50 MB copy you will. Don't fix it now — it's a known property of the design until content hashing
arrives — but recognise the symptom if a test flakes on a big file.

## Exit gate

The documented command reliably transfers a file, and a non-trivial automated test proves it.

## Question to sit with

Your transfer test exercises two completely different subsystems at once. **Which assertions in it
would still pass if the filesystem watcher were broken, and which would still pass if the TCP
framing were broken?**

If the answer is "none — any failure fails everything," that's the definition of a test with no
diagnostic value, and it's the reason chunks 2 and 3 split those two subsystems apart before adding
anything new.

## Reading

- `java.io.DataInput.readFully` contract — what it guarantees and what it doesn't:
  <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/DataInput.html#readFully(byte%5B%5D)>
- Li Haoyi, *Hands-on Scala Programming* — the file-synchronizer chapter maps almost exactly onto
  Phase 1 and is a useful reference implementation. See [`../03-resources.md`](../03-resources.md).
