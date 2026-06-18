# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A peer-to-peer file synchronization tool built **from scratch as a learning project** (a tiny Syncthing). The point is to implement every layer by hand — CRDTs, vector clocks, SWIM gossip, NAT traversal, Noise-encrypted transport — not to ship production software or reuse off-the-shelf sync libraries.

The codebase is currently at **Phase 0 (scaffolding)**: the only domain code is a placeholder `Main.scala` using scallop for CLI args and one trivial munit test. The real design lives in `docs/`, which is the most important context in this repo:

- `docs/01-overview.md` — goals, explicit non-goals, success criteria.
- `docs/02-plan.md` — the 10-phase roadmap. **This is the source of truth for what to build next.** Each phase has a "Done when" criterion; find the last completed phase and read its section to know where work stands.
- `docs/03-resources.md` — curated reading per phase (papers, reference implementations).
- `docs/04-implementation-notes.md` — cross-cutting technical decisions (concurrency model, library choices). Read before picking up coding.

When implementing a feature, check which phase it belongs to in `docs/02-plan.md` and respect that phase's scope — the plan deliberately defers things (e.g. delete/rename until Phase 2, real CRDT merge until Phase 4) to keep each milestone small.

## Hard constraints from the design (don't violate these)

- **No consensus algorithms.** No Raft, no Paxos, no quorums. Convergence comes from CRDT math, not coordination. This is intentional.
- **Don't roll your own crypto.** Phase 8 uses a vetted Noise library (`noise-java` on the Scala path).
- **Property-based testing is non-negotiable for the CRDT merge (Phase 4).** The merge function must be proven commutative, associative, and idempotent via property tests — not example tests.
- Stack is fixed: **Scala 3 + (cats-effect or Pekko)**. Don't switch languages mid-project.

## Commands

```bash
sbt compile        # compile
sbt run            # run Main (currently requires --apples, a placeholder arg)
sbt test           # run all tests
sbt console        # Scala 3 REPL

# Run a single test suite or test:
sbt 'testOnly MySuite'
sbt 'testOnly MySuite -- --tests=example'   # munit name filter
```

## Tech

- Scala 3.8.4, sbt. Dependencies declared in `build.sbt`: munit (test), scallop (CLI parsing).
- Sources under `src/main/scala`, tests under `src/test/scala`, mirroring standard sbt layout.