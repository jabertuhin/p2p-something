# Chunk 5 — Last-writer-wins and deletes

**Depends on:** chunk 4. **Unblocks:** chunk 6.
**Size:** a weekend. Almost all of it is pure code and tests.

## Why this chunk exists

Two peers edit the same file at the same moment. Both send. Both receive. Both must end up with the
same content, and they must get there **without talking to each other about it** — no locks, no
leader, no quorum, because `01-overview.md` rules all of that out on purpose.

The mechanism is small enough to state in one line: hold `(ts, originatorId)` per path, and accept an
inbound event only if its pair is strictly greater than the one you hold.

Take a moment on *why that works*, because it's the conceptual heart of the whole project and it's
easy to implement without seeing it.

**Tuple ordering is total.** For any two distinct pairs, exactly one is greater. Ties on `ts` are
broken by `originatorId`, and two events from the same peer at the same millisecond are the same
event. So there is no pair of competing events for which "which is greater" is undefined.

**The comparison is a function of the two values alone.** It doesn't consult the network, the order
of arrival, or anything either peer knows privately. Peer A comparing `(x, y)` and peer B comparing
`(x, y)` compute the same answer, necessarily — they're evaluating the same function on the same
inputs. That's the entire reason no coordination is required. Convergence isn't achieved by
agreement; it's achieved by both sides independently computing something that couldn't have differed.

**You are building a CRDT.** A per-path last-writer-wins register with a deterministic tiebreak is
exactly the LWW-Register from the Shapiro et al. taxonomy. What makes it one is that its merge is
commutative (arrival order doesn't matter), associative (grouping doesn't matter), and idempotent
(duplicates don't matter). Phase 4 formalises this and proves those laws with property tests. Here
you'll test them by example — but recognising that the example tests below are instances of three
algebraic laws is most of what Phase 4 is about.

And the cost, which is the point of doing it this way first: **one version is destroyed, silently**.
Not merged, not flagged, not saved as a conflict copy. Gone. This directly contradicts success
criterion 2 in `01-overview.md`. It's supposed to. Phase 2 is the milestone where you feel why LWW
is insufficient, so that Phase 3's vector clocks and Phase 4's conflict preservation have a motive.

## Spec

1. The sync core holds, per path, the `(ts, originatorId)` of the version it has accepted.
2. An inbound event is applied **iff** its `(ts, originatorId)` is strictly greater than the held
   pair (decision 3). Absent a held pair, it applies.
3. Ties on `ts` resolve to the higher `originatorId`. Both peers compute the same winner.
4. **Locally observed** events update the record too, not only applied inbound ones (decision 11).
5. Applying a delete removes the file **and retains** its `(ts, originatorId)` record (decision 5).
6. A delete of an already-absent path is a no-op that still records its version.
7. A redelivered event produces no state change and no disk write.
8. An event that loses is dropped silently — no error, no retry, no reply.
9. The apply path stays single-threaded; no per-path parallelism (decision 8).
10. A rename produces a delete of the old path followed by an upsert of the new (decision 4), and
    both halves reach the peer.

## Implementation guidance

**Extend the core from chunk 4; do not add a second component.** The guard record and the
accepted-version record are two fields of one state, and they interact — an inbound event that
*loses* must not register a guard entry, because no write happens and therefore no echo will come.
Splitting them across two components makes that interaction a cross-component invariant instead of
two lines in one function.

**The comparison is one expression.** Scala's `Ordering` for tuples gives you `(ts, id) > held`
directly, provided `originatorId` has a total order — a `UUID` does, as does its string form, but
pick one and be consistent, because `UUID`'s `compareTo` is famously *not* the same order as the
lexicographic order of its string. That inconsistency across peers would be a genuine divergence
bug, and a nasty one to find. It's a good argument for making the originator id a small wrapper type
with one ordering defined on it, rather than passing a raw `UUID` around.

**Deletes and the record.** After removing the file, the entry stays with the delete's
`(ts, originatorId)`. That entry *is* a tombstone. Decision 5's phrase "no tombstones" means nothing
*persisted* and nothing *replicated* — an in-memory record is required for correctness. Drop it and
a redelivered older upsert resurrects a deleted file, which is a data-integrity bug rather than a
convergence bug: both peers might even agree on the resurrected file.

Since nothing persists (Phase 5), tombstones vanish on restart. Note the consequence — a peer that
restarts and reconnects can be told about a file it deleted, and will accept it. That's a real gap,
correctly out of scope, and worth writing down where Phase 5 will find it.

**Recording local events** is the item most likely to be forgotten, and it fails in a
non-obvious direction. Suppose the record only updates on inbound applies. You edit `f.txt` at
`T=100` and send. A stale event for `f.txt` at `T=50` arrives from the peer. Your held pair is
whatever it was before your edit — say `T=10` — so `50 > 10` and you overwrite your own newer work
with the peer's older version. The peer, meanwhile, correctly rejects your `T=100`... no, it accepts
it, so it now has the new version while you have the old one. **The two peers have diverged**, which
is worse than losing an edit: the system no longer converges at all. One missing state update turns
a lossy-but-correct design into an incorrect one.

**Losing is not an error.** When an inbound event loses, do nothing — no log at error level, no
reply, no retry. In an eventually-consistent system, rejecting stale data is the *normal* path and
happens constantly. Logging it as a problem trains you to ignore your own logs. Debug level, if at
all.

## Test criteria

**Seam: the pure sync core.** This is the bulk of the chunk. Every case below is a plain function
call with hand-written timestamps — no clock, no IO, microseconds each.

Ordering:
- Inbound event newer than held → applied.
- Inbound event older than held → rejected, state unchanged.
- Inbound event with equal `ts` and higher `originatorId` → applied.
- Inbound event with equal `ts` and lower `originatorId` → rejected.
- No held record → applied.

The three CRDT laws, by example (Phase 4 will prove them properly):
- **Idempotent:** the same event delivered twice → applied, then no-op.
- **Commutative:** two competing events delivered in either order → identical final state. Write this
  as one test that runs both orders and compares.
- **Associative:** three events, grouped and applied in different arrangements → identical final
  state.

Clocks behaving badly:
- A peer's `ts` jumps backwards → its events lose until the clock catches up, and state is unchanged
  meanwhile. Assert the behavior; it's wrong-but-predictable, which is the Phase 2 contract.
- Both peers at the identical `ts` for the same path → exactly one wins, and both cores agree on
  which.

Deletes:
- Delete newer than held upsert → file removed, record retained with the delete's pair.
- Delete older than held upsert → rejected, file stays.
- Upsert newer than held delete → file restored (the "arguably wrong but convergent" case from
  decision 5 — assert it deliberately so nobody later "fixes" it by accident).
- Upsert older than held delete → rejected, file stays absent. **This is the resurrection test.** Drop
  the tombstone and it fails.
- Delete of an absent path → no-op, record still written.
- Delete delivered twice → no-op the second time.

Local events:
- A local event updates the record, so a subsequent older inbound event loses. **This is the
  divergence test from the guidance above** — the one that catches the missing state update.
- A local event and the guard interact correctly: a losing inbound event registers no guard entry,
  because no write occurred.

Rename, as a pair:
- Delete of `a`, then upsert of `b`, applied in that order → `a` absent, `b` present.
- The same pair applied in the *reverse* order → same final state. Ordering is guaranteed by
  decision 6, but if the outcome depends on it, you've found a latent bug that Phase 7's multi-peer
  work will expose.

**Seam: the peer, end to end.** Just enough to prove the core is actually wired in:
- A delete on A removes the file on B.
- Simultaneous upserts to the same path from both peers → both directories converge to the same
  bytes.

## Traps

**Timestamp granularity.** `System.currentTimeMillis()` has millisecond resolution, and two writes
in the same millisecond are common on a fast machine — this is why the tiebreak isn't decoration.
It's also why writing a test that *relies* on the real clock producing distinct values is flaky:
supply timestamps as data. Which the pure core lets you do, which is the point.

**Comparing with `>=` instead of `>`.** With `>=`, re-delivering the identical event re-applies it —
a disk write, a mtime change, and an echo the guard from chunk 4 has already consumed. Idempotence
is lost, and the symptom is a mysterious extra write.

**The record grows without bound.** Every path ever seen, including every deleted one, stays in the
map forever. At Phase 2 scale this is fine. Note it as unbounded; tombstone garbage collection is a
genuinely hard distributed-systems problem and it belongs to a later phase, not to a quick fix here.

**Path equality.** `a/b.txt` and `a//b.txt` and `./a/b.txt` are the same file and different map keys.
Normalise once, at the codec boundary in chunk 2, so the core never sees the question. On a
case-insensitive volume `A.txt` and `a.txt` are also the same file — note what your key does with
that; you don't have to solve it.

## Exit gate

Duplicates are idempotent, and both peers choose the same winner for every competing pair — proven
at the core seam, not inferred from a demo.

## Question to sit with

**Where did last-writer-wins visibly hurt?**

Keep a running list as you write these tests. Candidates you'll probably hit: the delete-then-edit
resurrection; a file saved on a laptop with a slightly fast clock always beating the desktop; the
fact that "concurrent" and "sequential" are indistinguishable to a wall clock, so an edit made in
full knowledge of another edit is treated identically to one made in ignorance of it.

That last one is the precise gap vector clocks fill, and Phase 3 is much easier to design when you
have three concrete examples in front of you instead of the general claim that clocks lie.

## Reading

- Shapiro, Preguiça, Baquero, Zawirski, *Conflict-free Replicated Data Types* (2011) — read the
  **LWW-Register** section now, and note the three laws. The rest can wait for Phase 4.
- Lamport, *Time, Clocks, and the Ordering of Events in a Distributed System* (1978) — short and
  readable, and it's the paper that explains why what you built here isn't enough. Best read
  *after* this chunk, when the shortcoming is concrete.
