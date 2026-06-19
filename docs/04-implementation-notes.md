# P2P File Sync — Implementation Notes

Running log of cross-cutting technical decisions, separate from the phase plan in `02-plan.md`. Add to this as decisions get made so they don't get re-litigated after a break.

## Concurrency model: when to adopt cats-effect

**Decision:** start in plain imperative Scala; adopt cats-effect + fs2 at Phase 2.

- **Phase 1 (toy sync):** plain Scala. A blocking watch loop and a blocking TCP socket. No cats-effect. Rationale: cats-effect is new to the author, and Phase 1 is a weekend toy — learn filesystem watching + wire protocols first, not two hard things at once.
- **Phase 2 onward:** introduce cats-effect + fs2 where concurrency actually bites — the loopback guard, bidirectional send/receive, and debouncing. The Phase 1→2 refactor (imperative loop → `fs2.Stream` of file events with `.debounce`) is a deliberate learning exercise.
- Long-term the daemon benefits from `Resource` (sockets, watch handles), `Queue`/`Topic` (fan-out to peers), and fs2 timers (SWIM, Phase 7). cats-effect/fs2 is also the more transferable skill vs. day-to-day data-engineering tooling.

## Filesystem watching: library choice

**Decision:** use [`io.methvin` directory-watcher](https://github.com/gmethvin/directory-watcher), not raw `java.nio.file.WatchService`.

- The author develops on **macOS**, where `WatchService` has no native backend — it falls back to `PollingWatchService` with multi-second latency, which makes Phase 1 feel broken. `fs2`'s `Files[F].watch` is built on `WatchService`, so it inherits this.
- `directory-watcher` uses native macOS FSEvents (via JNA), falls back to `WatchService` elsewhere, and has built-in debounce. Applies regardless of the effect-system choice above.
