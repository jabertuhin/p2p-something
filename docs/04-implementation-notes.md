# P2P File Sync — Implementation Notes

Running log of cross-cutting technical decisions, separate from the phase plan in `02-plan.md`. Add to this as decisions get made so they don't get re-litigated after a break.

## Concurrency model: when to adopt cats-effect

**Decision:** start in plain imperative Scala; adopt cats-effect + fs2 at Phase 2.

- **Phase 1 (toy sync):** plain Scala. A blocking watch loop and a blocking TCP socket. No cats-effect. Rationale: cats-effect is new to the author, and Phase 1 is a weekend toy — learn filesystem watching + wire protocols first, not two hard things at once.
- **Phase 2 onward:** introduce cats-effect + fs2 where concurrency actually bites — the loopback guard, bidirectional send/receive, and event coalescing. The Phase 1→2 refactor (imperative loop → `fs2.Stream` of file events) is a deliberate learning exercise. *(Originally this line said "→ `fs2.Stream` … with `.debounce`". See the coalescing note below: a global `.debounce` is the wrong operator here.)*
- Long-term the daemon benefits from `Resource` (sockets, watch handles), `Queue`/`Topic` (fan-out to peers), and fs2 timers (SWIM, Phase 7). cats-effect/fs2 is also the more transferable skill vs. day-to-day data-engineering tooling.

## Filesystem watching: library choice

**Decision:** use [`io.methvin` directory-watcher](https://github.com/gmethvin/directory-watcher), not raw `java.nio.file.WatchService`.

- The author develops on **macOS**, where `WatchService` has no native backend — it falls back to `PollingWatchService` with multi-second latency, which makes Phase 1 feel broken. `fs2`'s `Files[F].watch` is built on `WatchService`, so it inherits this.
- `directory-watcher` uses native macOS FSEvents (via JNA) and falls back to `WatchService` elsewhere. Applies regardless of the effect-system choice above.

**Correction (2026-08-23):** an earlier version of this note said `directory-watcher` "has built-in debounce". It does not. What it offers is **hash-based duplicate suppression** — it can hash a file and drop an event whose content is unchanged since the last one. That is a *content* filter. A debounce is a *time* filter: collapse everything seen within a window. They solve different problems and neither implies the other.

## Event coalescing: why not a global `.debounce`

**Decision (Phase 2):** do not put an `fs2` `.debounce` on the watcher stream. Coalescing, if needed at all, must be **per path**, and must never merge a delete with an upsert.

- `.debounce` operates on the *whole stream*. Save two different files 50 ms apart under a 200 ms window and one of them is discarded — not delayed, discarded. The watcher stream carries events for every path in the tree, so a global time filter is a global data-loss filter.
- It also breaks renames. A rename surfaces as `ENTRY_DELETE` + `ENTRY_CREATE` in quick succession, and the Phase 2 wire model (decision 4) depends on *both* halves being sent. A global window is exactly wide enough to eat one of them.
- Whether any time-based coalescing is needed, and how wide the window should be, is answered by characterizing the watcher first — not assumed. See `phase-2/chunk-1-watcher-characterization.md`.

## Step 0 record (2026-08-23)

Phase 2 dependency setup is complete and compiling:

- `cats-effect 3.7.0` and `fs2-core 3.13.0`. `fs2-core` is sufficient — decision 9 keeps socket and file I/O blocking inside `IO.blocking`, so `fs2-io` is not needed. `Queue` arrives transitively via cats-effect-std.
- `directory-watcher-better-files 0.19.0` (the better-files wrapper; `util/Watcher.scala` uses its `RecursiveFileMonitor`).
- `munit-cats-effect 2.2.0` (test). Plain munit cannot assert on an `IO`; this is needed the moment anything under test returns one.

## Phase 2 decision log

The full set of Phase 2 choices lives in [`phase-2/decisions.md`](phase-2/decisions.md), next to the chunk files that reference them by number. Three that outlive Phase 2:

- **Rename is not a wire event.** It decomposes to `Delete(from)` + `Upsert(to)`, because the watcher surfaces a rename as an uncorrelated `ENTRY_DELETE` + `ENTRY_CREATE`. Correlating them needs content hashing (Phase 4) or inode tracking; revisit when `content_hash` exists.
- **Apply path is single-threaded.** One inbound loop, no per-path parallelism, so same-path events have a well-defined order. Don't `parEvalMap` it.
- **Conflict logic lives in a pure core, IO lives in a thin shell.** The loopback guard is state inside that core, not a side effect in the watch loop. Everything interesting — the echo, the tie, the resurrection, the swallowed local edit — is then a pure-function test.

## Event timestamps: what orders a write

**Decision (Phase 2):** the ordering key is a `ts` captured with `System.currentTimeMillis()` when the peer *detects* an event — never the file's `mtime` from disk.

- `mtime` describes the file, not when this peer learned of the change. `cp -p`, unzip, and `touch -t` all produce an `mtime` older than the edit it should beat, so a genuinely newer version can lose.
- `mtime` still travels on the wire as metadata (restored on apply, useful in logs). It is not a decision variable.
- Keeping event `ts` as its own field means Phase 3 adds a `VectorClock` *beside* it rather than redefining what `mtime` meant. The physical `ts` stays useful as a tiebreak for genuinely concurrent versions.

