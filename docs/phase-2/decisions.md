# Phase 2 — Decisions

> Resolved up front so the wire format doesn't get rewritten twice, and so a break of a few weeks
> doesn't re-litigate them. The chunk files reference these by number.
>
> This file used to also carry a step-by-step plan; that has been superseded by the chunk files in
> this folder (see [`README.md`](README.md)). What remains here is the decision log.

---

1. **Originator id lifetime — per-run random UUID.** Generated at startup, not persisted.
   Phase 3 needs a *stable* node identity and will likely key it to the Phase 8 keypair, so
   persisting now is throwaway work. Ties are measure-zero and no state survives a restart anyway.

2. **The LWW timestamp — `System.currentTimeMillis()` captured at event-detection time.**
   `mtime` still rides on `Upsert` as metadata (restored on apply, useful in logs) but is *not* the
   decision variable. Rationale: mtime is a property of the file, not of when this peer learned of
   the change — `cp -p`, unzip, and `touch -t` all yield an mtime older than the edit it should beat,
   so a genuinely newer version would lose. Keeping a distinct event `ts` also lets Phase 3 add a
   `VectorClock` *beside* it rather than reinterpreting what mtime meant.

3. **Tiebreak — higher `originatorId` wins.** Formally: hold `(ts, originatorId)` per path and
   accept an inbound event iff `(in.ts, in.originatorId) > held`. Tuple ordering is total, so for any
   pair of competing events exactly one side accepts and both peers compute the same winner with no
   extra communication. Content-hash tiebreak was rejected: it pulls Phase 4's `content_hash` forward
   and changes no outcome (identical bytes at the same ms converge either way).

4. **Rename — decomposed into `Delete(from)` + `Upsert(to)`.** No `Rename` tag on the wire; exactly
   two message types. The deciding reason is not simplicity: **the watcher cannot identify a rename**.
   FSEvents/directory-watcher surface it as an uncorrelated `ENTRY_DELETE` + `ENTRY_CREATE`, so
   emitting a first-class `Rename` means correlating them yourself by content hash (Phase 4) or inode
   (a whole subsystem) — out of phase order. Cost accepted: bytes are re-sent (already the Phase 2
   baseline) and the "same file" signal is lost (nothing consumes it yet). The `02-plan.md`
   "done when" is still satisfied — rename on A leaves the old path gone and the new path present
   on B. Depends on inbound ordering, which decision 6 guarantees.

5. **Delete vs. concurrent edit — gap accepted, and it converges.** No *replicated* tombstones.
   A deletes at `T`, B edits at `T+1`: A receives `Upsert(T+1)`, beats its held `(T, idA)`, writes the
   file back; B receives `Delete(T)`, loses to its held `(T+1, idB)`, rejects. Both peers end with B's
   file. LWW is *consistent* here — the semantics are just arguably wrong. Tombstones in Phase 4.

   **Clarification (added after review):** the *in-memory* `(ts, originatorId)` record for a deleted
   path is retained after the file is removed. That retained record is a tombstone in everything but
   name; "no tombstones" above means nothing persisted and nothing replicated. Dropping the record
   along with the file permits stale resurrection on redelivery.

6. **Connection — single bidirectional socket; one peer listens, the other dials.** Both peers read
   and write that one socket. This is what makes decision 8 free: one inbound stream is one
   well-defined apply order, which the delete+upsert rename pair depends on. Two sockets would give
   two unordered inbound streams and a real reordering bug. Reconnect: only the dialer retries with
   backoff, which covers a restart of *either* side. Events produced while disconnected are **lost**
   — no outbox, no anti-entropy until Phase 6.

7. **Coalescing — strategy deferred to chunk 1, not chosen up front.**
   *Superseded wording:* this decision previously specified "debounce window ~200ms".

   Two corrections forced the change. First, `directory-watcher` provides **hash-based duplicate
   suppression**, which is not the same thing as a time-window debounce operator — the original note
   conflated them. Second, a *global* `fs2` `.debounce` is actively wrong here: it coalesces across
   the whole stream, so it can drop events for two different files saved in quick succession, or
   swallow one half of a rename's delete/create pair (decision 4 depends on both halves surviving).

   What is decided: coalescing, if any, must be **per path**, and must never merge a delete with an
   upsert. What is not decided: whether a time window is needed at all, and how wide. Chunk 1
   characterizes the watcher first; the answer comes from that record.

8. **Ordering — one outbound loop, one inbound loop, no per-path parallelism.** Explicitly do *not*
   `parEvalMap` the apply path. Guaranteed well-defined by the single socket in decision 6.

9. **cats-effect staging — minimum viable for Phase 2.** `IOApp`, plus a bounded `Queue` bridging
   directory-watcher's callback into an `fs2.Stream` (the `Queue` is needed regardless — the watcher
   is callback-based, not stream-based). Socket and filesystem I/O stay blocking inside `IO.blocking`.
   Defer `Resource`-wrapping sockets/watch and `Topic` fan-out to Phase 3+. What the stream does after
   the `Queue` is a chunk-1 question, per decision 7.

10. **The loopback guard lives in the pure sync core, not in the watch loop.** It is state
    (a record of what was just applied), not a side effect. This is what makes the nastiest race in
    the phase — apply a remote write, then genuinely edit the same path immediately — a deterministic
    unit test rather than a timing experiment. See chunk 4.

11. **The accepted-version record is updated by local events too, not only by applied inbound ones.**
    If only inbound events update it, an older remote event can overwrite a newer local write. See
    chunk 5.

---

## References

- Loopback / self-echo suppression is the canonical first bug — design the applied-set deliberately.
- Wall-clock unreliability motivates Phase 3 vector clocks; keep notes on where LWW *hurts*.
- Curated reading per phase lives in [`../03-resources.md`](../03-resources.md).
