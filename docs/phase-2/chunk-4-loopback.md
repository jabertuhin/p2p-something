# Chunk 4 — Reproduce and fix loopback

**Depends on:** chunks 1 and 3. **Unblocks:** chunk 5.
**Size:** a weekend. The first genuine distributed-systems bug in the project.

## Why this chunk exists

Turn the watcher on in the symmetric peer from chunk 3 and something specific happens. Peer A writes
a file. A's watcher fires, A sends an upsert. B receives it and writes to disk. **B's watcher fires**
— because a write is a write; the filesystem has no idea that one came from the network. B sends an
upsert back. A receives it, writes to disk, A's watcher fires, A sends. Forever, at whatever rate
your disk and socket can sustain.

That's the loopback problem, and it's worth being precise about why it's *interesting* rather than
merely annoying. The filesystem gives you no way to distinguish "the user changed this" from "I
changed this". The information you need — the provenance of a write — exists only in your own
program, a few microseconds earlier, in a different loop. Fixing it means carrying knowledge across
that gap. Every replication system has some version of this problem, and the shape of the fix
generalises: **remember what you caused, so you can recognise it coming back.**

### Reproduce it first

Do not skip this. Attach the watcher, run two peers, touch one file, and watch the logs fill.

The reasons are practical. You need to see what the echo actually looks like — how many events per
apply, at what interval, whether the library's hash-based duplicate suppression (chunk 1) already
kills some of them. You need to know whether an echo arrives *before* or *after* your apply
completes, because that determines whether a guard registered after the write is registered in time.
And you need the storm as a baseline: "it stopped" is only meaningful if you saw it start.

Bound the blast radius before you run it — a small file, and a log line per event so the rate is
visible. Two peers echoing a 100 MB file at each other will fill your disk cache and your patience.

## The invariant

"Loopback is fixed" needs to be a measurable statement, not a vibe. Use this one:

> **Quiescence.** After a single remote update is applied, the number of outbound events produced by
> that peer eventually returns to zero and stays there.

Two properties make this a good invariant. It's *observable* — count events at the transport, don't
read logs. And it's *falsifiable by the obvious wrong fix*: a guard that suppresses everything for
that path forever also satisfies quiescence, which is why the next section exists.

## The race that kills naive fixes

The obvious guard is "after applying to path P, drop the next watcher event for P". Now consider:

1. Peer A applies a remote update to `notes.txt`. Registers "drop the next event for `notes.txt`".
2. Before the watcher fires, **the user saves their own edit to `notes.txt`.**
3. The watcher fires — possibly once, coalescing both writes.
4. The guard drops it.

The user's edit is now gone. Not delayed — gone. It exists on A's disk and will never reach B, and
nothing in the system will ever notice. This is strictly worse than the storm you were fixing,
because the storm is loud and this is silent.

The lesson generalises past this bug: **a suppression rule keyed on "the next thing" is keyed on the
wrong thing.** You don't want to suppress the next event for that path; you want to suppress *the
event caused by your write*, which is a different set that usually — but not always — has one member.

## Designing the guard

So the guard needs to identify the specific write, not the path. Options, with their costs:

**Key on `(path, mtime)`.** When you apply an upsert you explicitly set the mtime to the value
carried on the event (decision 2, and chunk 3 spec item 6). So you know exactly what a stat of that
file will report *if nothing else has touched it*. Record `(path, appliedMtime)`. When a watcher
event arrives for that path, stat the file: if the mtime matches a recorded entry, it's your echo —
drop it and consume the entry. If it doesn't match, someone else wrote after you did, and the event
is real. The user's immediate edit in the race above changes the mtime, so it survives. This is the
recommended design.

**Key on `(path, contentHash)`.** Strictly more accurate — it catches the case where an edit
coincidentally produces an identical mtime, and it's immune to filesystem timestamp granularity. It
also pulls Phase 4's `content_hash` forward, and requires hashing every applied file. Note it as the
Phase 4 upgrade and move on.

**Pause the watcher around the write.** Cleanest-sounding, worst in practice. A genuine local edit
during the pause window is lost with no trace, `directory-watcher` may not expose a clean pause, and
FSEvents can deliver events from *before* the pause afterwards. Reject it, but know why.

**Suppress by content comparison at send time** — before sending, check whether what's on disk
differs from what you last applied. Elegant, and also Phase 4, for the same hashing reason.

Two properties any design needs, and both are easy to forget:

- **Entries are consumed.** One apply produces (usually) one echo. If the entry survives, a *later*
  genuine write that happens to match gets eaten.
- **Entries expire.** If the library's duplicate suppression eats the echo, or the event never
  arrives for any other reason, an unconsumed entry sits there indefinitely waiting to swallow
  something real. Bound it — by count, by age, or both.

And a case worth thinking through: chunk 1 may have told you that one apply produces *more than
one* event, especially if you adopted write-to-temp-then-rename. Then "one entry, consumed once" is
wrong and you need the entry to absorb several events, which brings expiry back to the centre. This
is precisely why chunk 1 comes first.

## Introducing the sync core

This is where the pure core appears, and where decision 10 pays off.

The temptation is to put the guard in the watch loop — a mutable set, checked before sending. It
would work. It would also make the race above testable only by running two real peers and timing
your keystrokes, which means it would never be tested, which means the next refactor breaks it
silently.

Instead: the guard is **state in a pure function**.

Shape it as a state machine. Its state holds the guard record (and, from chunk 5, the per-path
accepted-version record). Its single operation takes the current state plus one *input* and returns
a new state plus an *action*:

- Inputs: an event observed locally by the watcher, and an event received from the peer.
- Actions: apply this event to disk, send this event to the peer, or do nothing.

No clock, no socket, no filesystem — the timestamp is data on the event, and the mtime you'd stat is
supplied by the caller. The peer becomes a shell: read an input, call the core, execute the action.

The payoff is immediate. The race that was untestable becomes three function calls:

```
core(RemoteReceived(upsert))       → Apply
core(LocalObserved(echoOfThatApply)) → Ignore
core(LocalObserved(genuineEdit))   → Send        ← the assertion that matters
```

No threads, no sleeps, no ports. If you find yourself writing a test with a `sleep` in it, the logic
under test is in the wrong place.

## Spec

1. Applying an inbound event does not cause an outbound event for the same write.
2. A genuine local write to a path, occurring immediately after a remote apply to that same path,
   **does** produce an outbound event.
3. Quiescence holds: after one remote update, outbound event count returns to zero.
4. The guard is state inside the pure sync core, not a side effect in the watch loop (decision 10).
5. Guard entries are consumed when matched and expire when not.
6. The watcher is attached via a bounded queue into the outbound path (decision 9). Coalescing, if
   any, follows the conclusion recorded in decision 7 by chunk 1 — and is per path, never global.
7. Watcher events for paths that should never sync (editor temp files, if chunk 1 found them) are
   filtered by *path pattern*, not by the guard. Two different mechanisms; don't conflate them.
8. The trade-off of the chosen guard design is written into [`decisions.md`](decisions.md), including
   the case it does not handle.

## Test criteria

**Seam: the pure sync core.** These are the densest tests in Phase 2 and they run instantly.

- Remote apply, then its echo → the echo produces no send.
- Remote apply, then its echo, then a *second* echo for the same path → the second is not suppressed
  (unless chunk 1 established that one apply legitimately yields two events, in which case encode
  that number and justify it).
- Remote apply, then a genuine local edit with a different mtime → produces a send. **This is the
  most important test in the chunk.**
- Remote apply, then a genuine local edit, then the echo arrives late → work out what your design
  does here and assert it deliberately. There isn't a single right answer; there is a wrong answer,
  which is "I never thought about it".
- A local edit with no preceding remote apply → produces a send.
- Two remote applies to different paths, then both echoes → neither sends.
- An unconsumed guard entry, aged past the expiry bound, no longer suppresses.

**Seam: the peer, end to end.** Over the in-memory transport, two peers, two temp directories.

- One update on A propagates to B and the system goes quiet. Assert the *count* of events crossing
  the transport, not log content. A ping-pong bug shows up as an unbounded count.
- An update on A, then an update on B to a different path, both propagate, both directions quiet.

## Traps

**Debug the storm with counters, not logs.** At full rate the log is unreadable and logging itself
slows the loop enough to change the behavior you're observing.

**Your guard runs before you know whether the event is even relevant.** Order the outbound pipeline
deliberately: ignore-pattern filter, then guard, then coalescing, then send. Putting the guard after
coalescing means a coalesced event may represent both your write and the user's, and no key can tell
you that.

**Deletes echo too.** Applying a remote delete fires a watcher delete event, which broadcasts a
delete back. Your guard must cover deletes — and a deleted file has no mtime to stat, so a design
keyed on `(path, mtime)` needs an explicit story for that case. It's the first place chunk 5's work
reaches back into this one.

**A remote apply that changes nothing may produce no event at all.** If the applied bytes are
identical to what's on disk, the library's hash suppression may drop the echo silently. Your entry
then goes unconsumed — expiry, again.

## Exit gate

Remote application becomes quiescent, and an immediate genuine local edit still propagates. Both are
asserted by tests, not observed by eye.

## Question to sit with

**Your guard makes a claim: "the write I am about to see is mine."** On what evidence? Trace it
precisely — what fact, observable from the watcher event, distinguishes your write from an identical
write made by the user at the same instant?

If the honest answer is "nothing, but the window is small", you've found the real property of your
design: it's probabilistic. That's an acceptable Phase 2 answer. Write it down as one, because
Phase 4's content hashing is what makes it deterministic, and you'll want to remember why you wanted
that.

## Reading

- Syncthing's handling of its own writes — search their docs and issue tracker for how they avoid
  re-scanning files they just pulled. A decade of edge cases you're about to meet a few of.
- [`../05-review-2026-08-11.md`](../05-review-2026-08-11.md), "Design observations" — the naive-guard
  race is recorded there in its original form.
