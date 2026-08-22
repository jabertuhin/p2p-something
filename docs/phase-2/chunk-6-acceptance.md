# Chunk 6 — Acceptance

**Depends on:** chunk 5. **Unblocks:** Phase 3.
**Size:** an evening, plus a manual session with two terminals.

## Why this chunk exists

Phase 2's "done when" in [`../02-plan.md`](../02-plan.md) is one sentence:

> Two-way sync works for create/modify/delete/rename. Simultaneous edits to the same file resolve to
> one of the two versions deterministically (even though one is lost).

Chunks 0–5 each verified a piece at its own seam. None of them verified *that sentence*. The pieces
composing correctly is a separate claim from each piece being correct, and it's the claim that
justifies calling the phase finished.

There's also a discipline here worth naming. It is very easy, at the end of a phase, to run the demo
once, see the file appear, and declare victory. That's a test with a sample size of one, run by
someone who wants it to pass. The point of writing the acceptance criteria as automated tests is to
remove yourself from the loop — and to give Phase 3, which will refactor the core substantially, a
safety net that says whether it broke anything.

## Spec

Both of the following hold.

**The plan's criterion, mechanically verified.** Create, modify, delete, and rename each propagate in
both directions between two peers, and simultaneous edits to one path converge to one identical
version on both sides.

**The phase's own invariants, mechanically verified.** Quiescence after a remote apply (chunk 4);
idempotence under redelivery (chunk 5); nested paths and non-ASCII names (deferred from chunk 0);
reconnection after a dropped connection (chunk 3).

## Test criteria

**Seam: the peer, end to end, over the in-memory transport.** Two peers, two temporary directories,
deterministic. This is the acceptance suite.

The propagation matrix — each in *both* directions, so eight tests, and they should be written as a
table rather than eight copies of the same body:

| Operation | Assertion on the other peer |
|---|---|
| create | file exists with identical bytes |
| modify | file content updated to the new bytes |
| delete | file no longer exists |
| rename | old path absent **and** new path present with the original bytes |

Convergence:
- Simultaneous upserts to the same path from both peers, both delivered → both directories hold
  identical bytes, and it's one of the two versions rather than a mixture.
- The same scenario with delivery order reversed → same final state on both sides.
- Delete on A concurrent with an edit on B → both peers agree. Assert whichever outcome decision 5
  predicts, and add a comment saying it's the "arguably wrong, deliberately convergent" case so a
  future reader doesn't file a bug.

Invariants:
- Quiescence: after one update propagates, transport event count stops growing.
- Idempotence: replaying a captured event stream a second time changes nothing on disk.
- Nested path: a file in a subdirectory created *after* the peer started propagates. This is the case
  chunk 0 couldn't do with `WatchService`; it works now because of `RecursiveFileMonitor`, and
  asserting it is what proves the watcher migration actually completed.
- Non-ASCII filename propagates.
- Empty file propagates as empty.
- Connection drops mid-session, dialer reconnects, a subsequent change propagates. Note explicitly
  that changes made *while* disconnected are lost — decision 6 — so the test must make its change
  after reconnection. If you want, assert the loss too; it's a real property and Phase 6 will delete
  that assertion, which is a nice way to mark the boundary.

**Real sockets: one smoke test**, unchanged from chunk 3. Two peers on loopback, one change, it
arrives. Everything else stays on the in-memory transport where it can't flake.

## The manual session

Automated tests can't tell you whether the thing is *pleasant*, and some failures only show up under
a human. Run two peers in two terminals against two directories and spend twenty minutes:

- Save a file repeatedly from your actual editor. Count the events. If chunk 1's coalescing
  conclusion was wrong, this is where it shows.
- Create a folder, put files in it, rename the folder.
- Copy in something large. Watch memory. Note the pause.
- Delete a file on one side while editing it on the other.
- Kill one peer mid-transfer. Restart it. See what happens — and note that with no persistence
  (Phase 5) and no anti-entropy (Phase 6), what happens is that changes made during the gap are
  simply gone. Confirm the *shape* of that gap matches what you expect, because Phases 5 and 6 exist
  to close it and you want a concrete memory of what they're for.
- Leave both peers running idle for ten minutes. The logs should be silent. Any traffic at idle is a
  loopback bug that's slow rather than absent.

## Closing the phase

Once green:

- Mark Phase 2 done in [`../02-plan.md`](../02-plan.md), matching the status markers on Phases 0 and 1.
- Update `README.md`: the architecture diagram still shows the one-way Sender → Receiver flow, the
  usage section still documents the `send`/`receive` subcommands, and the status section describes
  Phase 1.
- Update `CLAUDE.md` and `AGENTS.md` again — chunk 0 moved them to Phase 1, and they're now stale
  in the other direction.
- Write the **LWW pain list** into [`decisions.md`](decisions.md) or a Phase 3 sketch: the concrete
  cases where wall-clock ordering gave a wrong-feeling answer. This is the single most valuable
  artifact this phase produces for the next one.
- Note what Phase 2 leaves broken, in one place, so Phase 3 doesn't rediscover it: no persistence,
  no anti-entropy, unbounded tombstone growth, whole-file transfer, probabilistic loopback guard,
  two peers only.

## Traps

**A test that passes because both sides did nothing.** Every convergence assertion needs to also
assert that the expected content is *present*, not merely that the two directories match. Two empty
directories converge beautifully.

**Timing-dependent assertions sneaking back in.** If an acceptance test needs a `sleep` to pass, the
in-memory transport isn't being used, or the peer isn't exposing a way to know an event was
processed. Fix the seam rather than lengthening the sleep — a sleep long enough to be reliable today
is a minute added to every future test run.

**Temp directory cleanup on failure.** A test that fails before its cleanup leaves directories
behind, and the next run may pick up their contents. Clean up in a `finally`-equivalent.

**Declaring done with a skipped test.** If something on the matrix doesn't work — rename in one
direction, say — the phase isn't done. Note it, fix it, or explicitly move it to Phase 3 in writing.
An ignored test is a lie that compiles.

## Exit gate

The sentence at the top of this file is true, and a test suite says so without you in the room.

## Question to sit with

**Which of these tests will Phase 3 break, and should it?**

Phase 3 replaces the `(ts, originatorId)` comparison with vector clocks, so every ordering test in
chunk 5 changes shape. But the tests in *this* file — create propagates, delete propagates, peers
converge — should survive untouched, because they describe what the system does rather than how.

That distinction is the practical definition of testing at the right altitude, and this is a good
moment to check whether you actually achieved it. If a Phase 3 refactor forces you to rewrite the
acceptance suite, the suite was coupled to the mechanism. Better to find that out now, by reading,
than in three weeks by editing.
