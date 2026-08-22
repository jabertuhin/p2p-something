# Phase 2 — Symmetric sync, delete/rename, last-writer-wins

**Start here.** This is the index for Phase 2. The work is split into seven chunks, each a
self-contained unit of spec + implementation guidance + test criteria, sized to be picked up and
finished without holding the rest in your head.

| # | Chunk | Status | What you learn | Depends on |
|---|---|---|---|---|
| — | **Setup** | ✅ done 2026-08-23 | deps added and compiling; see [`../04-implementation-notes.md`](../04-implementation-notes.md) | — |
| 0 | [Close Phase 1](chunk-0-close-phase-1.md) | 🚧 **in progress** — claimed 2026-08-23 | Why "it compiles" and "it works" are unrelated claims | — |
| 1 | [Characterize the watcher](chunk-1-watcher-characterization.md) | not started | What filesystems actually tell you, vs. what you assumed | — |
| 2 | [The wire model](chunk-2-wire-model.md) | not started | Designing a protocol as data, and parsing hostile input | 0 |
| 3 | [Symmetry without conflict resolution](chunk-3-symmetry.md) | not started | Two concurrent loops, one socket, cats-effect for real | 2 |
| 4 | [Reproduce and fix loopback](chunk-4-loopback.md) | not started | Your first genuine distributed-systems bug | 1, 3 |
| 5 | [Last-writer-wins and deletes](chunk-5-lww-deletes.md) | not started | Why a total order makes coordination unnecessary | 4 |
| 6 | [Acceptance](chunk-6-acceptance.md) | not started | Proving convergence instead of asserting it | 5 |

Chunks 0 and 1 are independent of everything else — do them in either order, or in parallel with
reading. From chunk 2 onward the order is load-bearing.

Update the Status column when you pick a chunk up and when you finish it. It's the "where did I
leave off" signal after a break, and it's cheaper than reconstructing state from `git log`.

Decisions that span chunks live in [`decisions.md`](decisions.md) and are cited by number
(*decision 4*, *decision 7*, …). If you find yourself re-arguing one mid-implementation, that's a
signal the decision was wrong, not that you should quietly deviate — update the file.

---

## Problem Statement

The author is building a peer-to-peer file synchronizer by hand in order to hit its distributed
systems problems personally. Right now that learning is blocked in three ways.

**The Phase 1 demonstration does not run.** The CLI's send branch reads the receive subcommand's
options, so the documented two-terminal demo fails on startup. The one-way sync that Phase 1 claims
to deliver has never actually been observed working end to end.

**Nothing is verified.** The only test asserts `42 == 42`. The wire codec, the filesystem watcher,
and the transfer loop are all unproven, so a green build says nothing. When Phase 2 misbehaves,
there will be no way to tell a new bug from a pre-existing one.

**Sync only flows one way, and only for creates and modifies.** One process watches and pushes; the
other accepts and writes. A change on the receiving side is invisible to the sender. Deletes and
renames do not propagate at all. There is no notion of *who* produced an event or *when* it was
detected, so there is no way to decide which of two competing writes to a path should win — and no
guard preventing a peer from re-broadcasting a change it just applied itself, which will loop
forever the moment the second direction is switched on.

## Solution

Close Phase 1 by repairing the CLI wiring and putting the wire codec under test, then replace the
asymmetric Sender/Receiver pair with a single symmetric **peer**.

Both peers watch their own sync root, and both send and receive over one bidirectional connection.
Setup stays asymmetric — one side listens, the other dials — but once connected the roles are
identical. Every change becomes a typed **event** carrying the path, the **originator id** of the
peer that detected it, and an **event timestamp** captured at detection time. Two event types cover
everything: an **upsert** (create or modify, carrying bytes and the file's mtime as metadata) and a
**delete**.

On receiving an event, a peer compares its `(timestamp, originatorId)` against the pair it last
accepted for that path and applies the event only if the inbound pair is strictly greater. Tuple
ordering is total, so both peers independently compute the same winner for any pair of competing
writes with no extra communication — **last-writer-wins**. One version is silently lost, which is
the intended, instructive pain of this phase.

Applying a remote change writes to disk, which the local watcher will notice. A **loopback guard**
records what was just applied so the outbound path drops that echo instead of broadcasting it back.

A rename is not a wire event: the watcher cannot recognise one, so it decomposes into a delete of
the old path and an upsert of the new.

The result is a sync that works in both directions for create, modify, delete, and rename, and that
converges deterministically when both sides edit the same file at once.

---

## Architecture

```
            ┌─────────────────────── Peer ───────────────────────┐
            │                                                    │
  fs watch ─┼─▶ events ─▶ [ SYNC CORE ] ─▶ outbound ─────────────┼─▶ transport ─▶ other peer
            │                  ▲  │                              │
            │                  │  └─▶ (drop: this was our echo)  │
            │                  │                                 │
            │            state: per-path (ts, originatorId)      │
            │                   + recently-applied record        │
            │                  │                                 │
  disk ◀────┼── apply ◀────────┴──────────── inbound ◀───────────┼── transport ◀── other peer
            └────────────────────────────────────────────────────┘
```

The important structural claim: **everything interesting is in the sync core, and the sync core is
pure.** It sees no clock, no socket, and no filesystem — timestamps arrive as data on events. The
peer around it is a thin shell that runs two loops and executes whatever action the core returns.

Both directions flow through the same core, which is what lets it suppress echoes: it knows what it
just told the disk to do, so it recognises the watcher event that write causes.

## The three seams

Everything in Phase 2 is tested at one of three boundaries. Nothing else gets a test.

1. **The codec** — bytes ↔ event. Pure. Round-trips and hostile input. See chunk 2.
2. **The sync core** — `(state, input) → (state, action)`. Pure, no clock, no IO. Every conflict,
   tie, duplicate, resurrection, and loopback case is a plain function call here. Densest and most
   valuable tests in the phase. See chunks 4 and 5.
3. **The peer, end to end** — two peers, two temp directories, over an in-memory transport. Proves
   the whole thing composes. One additional smoke test uses a real socket. See chunks 3 and 6.

A good test at these seams asserts external behavior: given these inputs, this observable outcome.
It does not reach for internal fields and does not assert on log output. If a test has to be
rewritten because the core was refactored without changing what it *decides*, it was testing the
wrong thing.

**Deliberately untested: the watcher itself.** Chunk 1 characterizes it as a written experiment,
because asserting on real filesystem event sequences pins the behavior of macOS, not of this
project. The watcher's output is reduced to events at the earliest possible point; everything after
that point is tested.

---

## Out of Scope

- **Content hashing** and skip-if-unchanged. Phase 4's `content_hash`. Pulling it forward would also
  change the rename decision.
- **Vector clocks and concurrency detection.** Phase 3. Last-writer-wins is meant to feel lossy here;
  that loss is the motivation for the next phase.
- **Conflict preservation.** No conflict copies, no multi-value register. One version is discarded,
  by design.
- **Sub-file or block-level transfer.** Whole files are re-sent, including for renames.
- **Persisted state.** No on-disk metadata store. Accepted-version records and tombstones live in
  memory and vanish on restart.
- **Anti-entropy and outboxes.** Changes made while disconnected are lost. Reconciliation is Phase 6.
- **More than two peers.** Phase 7.
- **Encryption, authentication, peer discovery.** Phases 7–9. Transport is plaintext TCP and the peer
  address comes from the command line.
- **A first-class rename event.** Excluded because the watcher cannot identify a rename (decision 4),
  not for simplicity.
- **Symlinks, permissions, ownership, extended attributes.** Regular files, content, and mtime only.
- **Performance work.** No throughput or memory targets. The maximum-payload constant is a safety
  bound, not a tuning parameter.

## Two things to watch as you build

**The sync core is where the learning lives.** Every interesting failure — the echo, the tie, the
resurrection, the swallowed local edit — is a decision that core makes with no IO involved. If logic
starts leaking into the peer loops, the tests get slower and vaguer and the phase gets harder to
reason about. Keep the shell thin.

**The delete-versus-concurrent-edit outcome is convergent but arguably wrong.** A delete at `T`
followed by an edit at `T+1` resurrects the file on both peers. That is correct last-writer-wins
behavior and it should not be "fixed" in this phase. Record where it feels wrong; that record is the
input to Phase 4.

Keep a running note of where wall-clock ordering visibly hurts. Phase 3 is far easier to motivate
with concrete examples you collected than with the general argument that clocks lie.

---

## How to work through a chunk

Each chunk has the same five sections:

- **Why this chunk exists** — the concept, and what it will feel like when you get it wrong.
- **Spec** — what must be true when you're done, as observable statements. No code.
- **Implementation guidance** — the shape of the modules, in prose. Interfaces named, not written.
- **Test criteria** — the specific cases, at a named seam.
- **Exit gate** — the one thing that has to hold before moving on.

You write the code. Per [`../../CLAUDE.md`](../../CLAUDE.md), the value of this phase is in hitting
the loopback bug, the delete-versus-edit race, and the tiebreak personally — so the chunks describe
shapes and traps, not implementations. Ask for review after each one.
