# Chunk 1 — Characterize the watcher

**Status:** 🚧 in progress — claimed 2026-08-29.

**Depends on:** nothing. **Unblocks:** chunk 4 and settles decision 7.

**Goal:** measure what macOS FSEvents emits through `directory-watcher`, then choose a coalescing
strategy from evidence. This chunk produces notes, not production code or automated tests.

## Checklist

### Prepare the watcher spike

- [ ] Extend `util/Watcher.scala` or create a throwaway `main`.
- [ ] Log each event's kind, path, `count`, and `System.nanoTime` delta.
- [ ] Use the existing logger so timestamps are consistent.
- [ ] Record the library's FSEvents latency and whether hashing is enabled by default.

Run each scenario separately, with a pause between runs. For every scenario, record the ordered
events, paths, counts, and approximate time gaps in
[`docs/04-implementation-notes.md`](../04-implementation-notes.md).

### Run the experiments

- [ ] **Create:** `echo "hello" > f.txt`
- [ ] **Append:** `echo "world" >> f.txt`
- [ ] **Delete:** `rm f.txt`
- [ ] **Rename inside:** `mv f.txt g.txt`
- [ ] **Move out:** `mv f.txt ../outside.txt`
- [ ] **Move in:** `mv ../outside.txt f.txt`
- [ ] **Simple editor save:** save an existing file using `nano` or VS Code with atomic save off.
- [ ] **Atomic editor save:** save using Vim defaults or VS Code atomic save; record every temporary
  and backup path.
- [ ] **Preserved mtime:** copy a file in with `cp -p`; confirm its mtime can predate the event.
- [ ] **Large copy:** copy a file of at least 100 MB; note event count and whether an event arrives
  before the copy completes.
- [ ] **New subdirectory:** run `mkdir sub`, then `echo hi > sub/f.txt`; note whether the file event
  appears.
- [ ] **Burst across paths:** touch five different files in a tight loop; confirm whether all five
  events appear.

Optional but useful: rename `Foo.txt` to `foo.txt` and record the behavior on the default
case-insensitive macOS filesystem.

### Record conclusions

- [ ] Does one logical save produce duplicate events?
- [ ] If so, are the duplicates limited to the same path?
- [ ] How far apart are the delete and create events for a rename?
- [ ] Which temporary or backup paths need ignore rules?
- [ ] Does hash-based duplicate suppression remove the duplicates already?
- [ ] What does the callback's `count` value represent?
- [ ] Should directory events be ignored? Record that empty directories will then not sync.
- [ ] Replace the deferred wording in [decision 7](decisions.md) with the chosen strategy and its
  measured rationale.
- [ ] Remove the throwaway spike if it is no longer useful.

## Decision rules

- If one logical save produces one useful event, do not add coalescing.
- If same-path duplicates remain, consider per-path coalescing.
- Never use a global `fs2.Stream.debounce`: activity on one path can discard an event for another.
- Never coalesce a delete with an upsert; a rename depends on both events surviving.
- Use path ignore rules—not timing—to suppress editor temporary files.
- Prefer the library's hash suppression if it already solves the observed duplicate problem.

## Guardrails

- Filesystem events describe mechanics, not user intent. One editor save may touch several paths.
- FSEvents and the library may coalesce events before the callback receives them. Record the latency
  and hashing settings so the experiment is reproducible.
- A directory `MODIFY` event does not describe which child changed.
- Do not write automated assertions for these sequences. They vary by OS, filesystem, machine load,
  and library configuration. The written characterization is the permanent artifact; the watcher
  remains outside the project's test boundary.

## Exit gate

The implementation notes contain reproducible results for every scenario, each observed event is
classified as filesystem mechanics or likely user intent, and decision 7 specifies a concrete
coalescing strategy—or explicitly chooses none.

## Reading

- [`directory-watcher` README](https://github.com/gmethvin/directory-watcher) — hashing options.
- Apple's FSEvents programming guide — latency and event coalescing.
- Syncthing ignore-pattern documentation — handling editor temporary files.
