# Chunk 3 — Symmetry without conflict resolution

**Depends on:** chunk 2. **Unblocks:** chunk 4.
**Size:** a weekend. This is the cats-effect chunk.

## Why this chunk exists

Two things become true here at once, and the trick is to learn them one at a time.

The first is **symmetry**. Phase 1 has a sender and a receiver: two programs, two roles, one
direction. Phase 2 has one program that does both. The asymmetry that survives is only in *setup* —
somebody has to listen and somebody has to dial, because TCP is like that — and once the connection
exists the two sides are indistinguishable. Getting this right means the concept "which peer am I"
disappears from your code entirely except in the few lines that obtain the socket.

The second is **cats-effect**. You need it here because you now have two things that must happen at
the same time, forever, and blocking loops can't do that. This is the right moment to learn it: the
concurrency requirement is real and small. One `IOApp`, two loops running concurrently, a queue
between the callback world and the stream world. Nothing else yet.

**Deliberately excluded from this chunk:** the watcher, conflict resolution, the loopback guard, and
event coalescing. You will drive the outbound path with *injected* events — typed in, read from
stdin, whatever's convenient. This is the point. If the watcher were attached, an inbound event
would cause a disk write, which would cause a watcher event, which would go back out, and you would
be debugging the loopback storm and the connection lifecycle simultaneously. Chunk 4 turns the
watcher on deliberately, so that when the storm appears you know exactly what caused it.

### What cats-effect actually buys you

Worth understanding before you write any of it, since the shape of the code otherwise looks like
ceremony:

**`IO[A]` is a description of a computation, not a running one.** `IO { println("hi") }` prints
nothing. It's a value you can pass around, combine, and eventually hand to the runtime. This is why
`IOApp` exists — it's the one place execution actually starts. The practical consequence: `.flatMap`
sequences, and *nothing happens* until the whole description reaches `run`. Beginners lose an hour
to a forgotten `unsafeRunSync` or a discarded `IO` value; if something isn't happening, check that
you actually returned it.

**`IO.blocking` is not decoration.** The cats-effect runtime has a compute pool sized to your core
count and a separate unbounded blocking pool. A blocking call — `socket.read`, `Files.readAllBytes`,
`ServerSocket.accept` — parked on a compute thread removes that thread from service. Do it on
enough threads and the entire runtime deadlocks, including things unrelated to IO. Wrapping in
`IO.blocking` moves the work to the pool that's designed to have threads sitting idle. Every socket
and filesystem call in this project belongs inside one (decision 9).

**Fibers are why two loops are cheap.** A fiber is a green thread the runtime schedules onto real
threads. Starting two infinite loops as fibers costs almost nothing, whereas two OS threads per
peer stops scaling around Phase 7. You mostly won't touch fibers directly — the combinator that
runs two `IO`s concurrently and cancels both if either fails does the work for you.

**Cancellation is the part that surprises people.** When one loop dies, you want the other to stop.
cats-effect gives you that automatically with the right combinator, and getting it wrong shows up
as a process that won't exit. If your peer hangs on shutdown, the answer is almost always that a
blocking call isn't cancellable — `IO.blocking` can only be interrupted at boundaries, and a thread
parked in `socket.read` will sit there until the socket closes. Closing the socket *is* the
cancellation mechanism for this kind of code.

## Spec

1. One CLI command runs a peer. It takes the sync directory plus exactly one of a listen port or a
   connect target; supplying both, or neither, is an error at startup (decision 6).
2. The `send` and `receive` subcommands are gone.
3. A peer generates its originator id at startup as a random UUID, and does not persist it
   (decision 1).
4. A peer runs an outbound loop and an inbound loop concurrently over **one** bidirectional
   connection (decision 6).
5. Injected events sent from either side arrive and are applied on the other side. Both directions
   work over the same connection.
6. Applying an inbound upsert writes the bytes at the corresponding relative path, creating parent
   directories, and restores the mtime carried on the event.
7. When the connection drops, the dialing peer retries with backoff. The listening peer accepts
   again (decision 6).
8. Connection established and connection lost each produce exactly one log line.
9. If either loop terminates, the other stops too — the peer does not sit half-alive.

## Implementation guidance

### The transport seam

Introduce a narrow interface with two operations — send one event, receive one event — plus a way to
close. Two implementations: one over a real socket, one over an in-memory pipe.

This is the most important structural decision in the chunk, and it's worth being clear about why
it's not over-engineering. Testing connection *lifetime* behavior — clean close, abrupt drop,
reconnect — against a real socket means binding ports, racing accepts, and waiting on timeouts. Every
such test is slow and occasionally flaky, and flaky tests get deleted. Behind the interface, "the
peer disconnected" is a method call. You get to test the thing that's actually hard.

Keep the interface *narrow*. It sends and receives events; it knows nothing about sync, paths,
timestamps, or conflict. If sync logic starts appearing in the socket implementation, the seam has
moved to the wrong place.

Note what the in-memory implementation must faithfully reproduce: **ordering** (events arrive in the
order sent — decision 6 depends on this) and **termination** (a closed transport signals end-of-
stream to the reader rather than blocking forever). It need not reproduce latency, partial reads, or
buffering; those belong to the codec, which chunk 2 already tested.

### The two loops

Outbound: take an injected event, hand it to the transport. Inbound: take an event from the
transport, apply it to disk.

Run them with a combinator that executes both concurrently and cancels the other when either
finishes or fails. Do not start two fibers and forget about them — an un-awaited fiber whose
failure you never observe is a silent failure, and you'll spend an evening wondering why sync
stopped when actually the inbound loop died twenty minutes ago.

### The queue

Even without the watcher, put a **bounded** `Queue` in front of the outbound loop and inject into
it. Two reasons: the watcher in chunk 4 is a callback and a queue is the only sane way to get a
callback into a stream, so you'll need it regardless; and bounding it forces you to answer "what
happens when the peer is slower than the filesystem?" now rather than under load.

The answer at this scale is backpressure — a full queue makes the producer wait. Note the
consequence out loud, though: a callback that blocks is a callback that stalls the watcher thread.
`Queue.bounded` with an offer that waits, versus `tryOffer` that drops, is a real choice with real
data-loss implications. Whichever you pick, write down what happens when the queue is full.

### Connection lifecycle

Only the dialer retries (decision 6). Exponential backoff with a cap; log each attempt at a level
you can turn off. A tight reconnect loop against a dead peer will fill a terminal in seconds.

Two ways a connection ends and they need different handling: **clean EOF** (the peer closed; your
read returns end-of-stream) and **abrupt failure** (reset, timeout, cable pulled; your read throws).
Both mean "reconnect", but only the second is worth logging as an error.

Don't reach for `Resource` yet (decision 9). A `try`/`finally`-shaped acquire-and-release is fine
here and you'll appreciate what `Resource` does for you more after you've hand-rolled the awkward
version.

## Test criteria

**Seam: the peer, end to end, over the in-memory transport.** Two peers, two temporary directories,
no sockets.

- An injected upsert on peer A appears on disk at peer B with identical bytes.
- An injected upsert on peer B appears on peer A. Same connection, opposite direction.
- Both directions work interleaved — A sends, B sends, A sends — with no interference.
- An upsert with empty content produces an empty file.
- An upsert to a nested relative path creates the parent directories.
- The applied file's mtime matches the mtime carried on the event.
- Closing the transport terminates both loops. Assert the peer's `IO` actually completes; a test
  that hangs here is telling you something true.
- After a transport failure, the dialing peer attempts a reconnect. Assert the *attempt*, not a
  wall-clock delay — a test that sleeps for the backoff is a slow test that will flake.

**One real-socket smoke test.** Two peers on loopback, one injected upsert, assert it arrives. That
is the entire scope: it proves the socket implementation is wired up. Every other behavior is
covered above, deterministically. Keeping port binding and timing confined to a single test means
you have exactly one place to look when CI goes red for environmental reasons.

## Traps

**The CLI's mutual exclusion needs to be enforced, not documented.** Scallop supports this directly;
find the combinator rather than writing an `if` that runs after `verify()`. Chunk 0's bug was
precisely a runtime read that the type system couldn't see.

**Applying to disk is not atomic.** Writing bytes directly to the destination path means a reader
can observe a half-written file, and a crash leaves it truncated. The standard fix is write-to-temp
then rename, since rename within a filesystem is atomic. You don't strictly need it in Phase 2 —
but note that if you *do* add it, you've just created a second event on the watcher (create temp,
rename) which chunk 4's loopback guard must account for. Either choice is fine; making it
accidentally is not.

**Restoring mtime after writing is what makes chunk 6's assertions meaningful**, and it's easy to
get the ordering wrong — set the mtime *after* the write completes, or the write overwrites it.

**A peer syncing to itself.** Nothing stops you pointing both peers at the same directory. It will
behave bizarrely. Not worth guarding against, but worth recognising when it happens by accident
during manual testing.

**Bytes in memory.** An upsert holds the whole file in a byte array, at both ends, plus once more in
the queue. Three copies of a large file. This is the known Phase 2 limitation from `02-plan.md`'s
parked optimization; note where it hurts rather than fixing it.

## Exit gate

Either peer can send an injected upsert and see it land on the other side, and disconnecting one
side restarts or terminates both loops predictably — no hangs, no zombie fibers, no silent death.

## Question to sit with

**Your two loops share a socket. What happens if the outbound loop is mid-way through writing a
large upsert when the inbound loop wants to read?**

Reading and writing a TCP socket from two threads is safe — they're independent directions. But two
*writers* interleaving on one output stream would corrupt the frame boundaries chunk 2 built. You
have one writer today. Note where that assumption lives, because Phase 7 adds a second thing that
wants to send (gossip), and it will land right on top of it.

## Reading

- *Essential Effects* (Adam Rosien), chapters on `IO`, concurrency, and fibers — the standard
  on-ramp, and short. See [`../03-resources.md`](../03-resources.md).
- cats-effect docs on the thread model: <https://typelevel.org/cats-effect/docs/thread-model> —
  read this one properly. It's the difference between using `IO.blocking` because you were told to
  and knowing what happens if you don't.
- fs2 guide, the section on `Queue` and converting callbacks into streams.
