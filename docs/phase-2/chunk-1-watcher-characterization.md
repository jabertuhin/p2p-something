# Chunk 1 — Characterize the watcher

**Depends on:** nothing. **Unblocks:** chunk 4 (and settles decision 7).
**Size:** an evening. Throwaway code, permanent notes.

## Why this chunk exists

Every design choice downstream of the watcher rests on an assumption about what the watcher emits.
Right now those assumptions are guesses, and at least two of them have already turned out wrong:

- The implementation notes claimed `directory-watcher` "has built-in debounce". It does not. It
  offers **hash-based duplicate suppression** — hash the file, drop the event if the content hasn't
  changed. That's a *content* filter. A debounce is a *time* filter. Neither implies the other, and
  the two fail in completely different ways.
- Decision 7 originally specified a 200 ms `fs2` `.debounce` on the watcher stream. That operator is
  global: it coalesces across the whole stream, not per path. Save two different files 50 ms apart
  and one of them is **discarded** — not delayed, discarded. Worse, decision 4 decomposes a rename
  into a delete followed immediately by an upsert, and a global window is precisely wide enough to
  eat one half of that pair.

So: measure first. This chunk produces no production code. It produces a written record of what
macOS actually does, and that record is what chunk 4 designs against.

**The deeper idea.** Filesystem events describe *mechanics*, not *intent*. When you save a file in
Vim, you intend one thing: "this file now has these contents." What the filesystem sees might be:
create `.file.txt.swp`, write it, create `file.txt~`, rename `file.txt` → `file.txt~`, rename
`.file.txt.swp` → `file.txt`, delete `file.txt~`. Six events for one intention, involving three
paths, two of which you never want to sync. Learning to read the mechanics and infer the intent is
the actual skill here, and no library does it for you.

## Spec

A written record, added to [`../04-implementation-notes.md`](../04-implementation-notes.md), giving
the exact event sequence macOS FSEvents (via `directory-watcher`) produces for each scenario below.
For each: the event kinds, in order, with the paths involved, and the approximate timing between
them.

Scenarios to run:

1. `echo "hello" > f.txt` — create a new file via shell redirect.
2. `echo "world" >> f.txt` — append to an existing file.
3. `rm f.txt` — delete.
4. `mv f.txt g.txt` — rename within the watched tree.
5. `mv f.txt ../outside.txt` — rename *out* of the watched tree.
6. `mv ../outside.txt f.txt` — rename *into* the watched tree.
7. Save an existing file from a "simple write" editor (`nano`, VS Code with atomic save off).
8. Save an existing file from an atomic-save editor (Vim with default settings, or VS Code with
   `files.useExperimentalFileWatcher`/atomic save on). Note every path that appears, including
   temp and backup files.
9. `cp -p` a file in from elsewhere — note that mtime is preserved and therefore *older* than the
   event.
10. Copy a large file (100 MB+) in. How many events? Does one arrive before the copy finishes?
11. `mkdir sub` then `echo hi > sub/f.txt` — does a file in a *newly created* subdirectory get
    seen? (This is the recursion question chunk 0 deferred.)
12. Touch 5 different files in a tight loop. Do you get 5 events?

## Implementation guidance

Extend the existing `util/Watcher.scala` spike or write a throwaway `main` beside it — either is
fine, this code does not survive the chunk. Two changes make it useful:

- Print a monotonic timestamp with each event. `System.nanoTime` deltas matter more than absolute
  time; you're looking for "these two events were 3 ms apart" versus "300 ms apart".
- Print the `count` parameter that `RecursiveFileMonitor.onEvent` gives you, and find out what it
  actually counts. It's currently only printed for modify events.
- Switch the `println`s to the logger that file already imports, so timestamps are consistent.

While you're in there, look at `directory-watcher`'s builder options — specifically whether file
hashing is on by default in the better-files wrapper. That setting changes the answer to scenarios
2, 7, and 10, so record which mode you measured in.

Run each scenario in isolation with a pause between them. Batched-together events from two
scenarios will read as one confusing sequence.

## Test criteria

**None.** This chunk deliberately produces no automated tests.

Asserting on real filesystem event sequences pins the behavior of macOS, not of this project. Such
a test fails when you upgrade the OS, when the machine is under load, when the test runs on CI under
Linux, and when a colleague runs it on a case-insensitive volume. The watcher stays outside the
test boundary permanently — see the seams section in [`README.md`](README.md).

What replaces the test is this document. The record is the deliverable.

## What to decide from the record

Once you have the data, settle decision 7 by answering:

- **Does a single logical save produce more than one event?** If not, no coalescing is needed at
  all and decision 7 collapses to "none". Don't build a debouncer you don't need.
- **If yes, are the duplicates for the same path?** Per-path coalescing is safe. Anything global is
  not.
- **How far apart are a rename's delete and create?** That interval is a hard lower bound on any
  window you might consider, and if it's large, a window can't distinguish the pair from an
  unrelated delete-then-create anyway.
- **Do the atomic-save temp files (`.swp`, `~`, `.tmp`, `4913`) show up as events?** If so you need
  an ignore rule, which is a filter on *paths*, not on time — a different mechanism entirely, and
  one that never risks dropping real events.
- **Does hash-based duplicate suppression already solve the duplicate-save problem?** If the library
  filter handles it, you need nothing in your own code.

Write the conclusion into decision 7 as a replacement for the "deferred" wording.

## Traps

**Don't reach for `.debounce` because it's the operator you know.** The reason it's wrong here is
specific and worth internalising: `fs2`'s `debounce` maintains one timer for the whole stream, so
its notion of "a burst" is "anything, anywhere, within the window". Your stream is multiplexed
across every path in the tree. An operator that coalesces a multiplexed stream without grouping by
key loses data by construction. If you do need time-based coalescing, the shape you want is
"group by path, then debounce each group" — and that's a much bigger commitment than one operator.

**FSEvents is coalescing before you ever see it.** macOS delivers directory-level notifications with
its own latency parameter, and the library reconstructs per-file events from them. So "how many
events per save" partly depends on a knob inside the library. Note the knob's value alongside your
measurements or the numbers aren't reproducible.

**A `MODIFY` on a directory is not a modify on its contents.** You'll see directory events. Decide
now what you do with them — almost certainly ignore, since directories carry no content in this
model — and note that an empty directory therefore never syncs. That's an accepted gap, but it
should be a known one rather than a surprise in chunk 6.

**Case-insensitive filesystems.** macOS's default volume treats `Foo.txt` and `foo.txt` as the same
file; Linux does not. If you rename to a case-only variant, note what happens. This will bite in a
later phase when peers run different operating systems; recording it now costs nothing.

## Exit gate

You can point at your notes and say, for each event in a sequence, whether it represents filesystem
mechanics or user intent — and decision 7 has a concrete answer rather than "deferred".

## Reading

- `directory-watcher` README, particularly the hashing option:
  <https://github.com/gmethvin/directory-watcher>
- Apple's FSEvents programming guide — skim the section on latency and event coalescing. It explains
  why the library can't give you a clean one-event-per-save guarantee.
- Syncthing's docs on how it handles editor atomic saves — the ignore-pattern approach is the
  practical answer most sync tools converge on.
