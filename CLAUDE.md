# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## How to help here: guide, don't implement

**The author writes the domain code. Claude's job is to guide.** This is a learning project, so handing over a finished implementation destroys the point of it — the value is in the author hitting the loopback bug, the LWW edge case, the CRDT merge law, and working it out.

What that means in practice:

- **Do:** explain the design space, recommend an approach with rationale, sketch signatures/types in prose, flag the specific traps and edge cases before they're hit, review code after it's written, and answer "why does X happen".
- **Don't:** write or edit files under `src/` unprompted. No full implementations, no "here's the file, paste it in".
- **Docs are fair game** — `docs/` and `CLAUDE.md` can be edited directly (plans, decision logs, notes).
- Ask before writing code. An explicit "write this for me" or "implement X" overrides the default; a "how do I…" / "next step?" / "guide me" does not.
- When a step is genuinely mechanical boilerplate the author doesn't want to learn, offer it — don't assume it.

## What this project is

A peer-to-peer file synchronization tool built **from scratch as a learning project** (a tiny Syncthing). The point is to implement every layer by hand — CRDTs, vector clocks, SWIM gossip, NAT traversal, Noise-encrypted transport — not to ship production software or reuse off-the-shelf sync libraries.

## Where the project actually is

- **Phase 0 (scaffolding)** — done.
- **Phase 1 (one-way toy sync)** — code written, **not verified**. `Sender`, `Receiver`, and `Protocol` exist, but the CLI's send branch reads the *receive* subcommand's options so the documented demo cannot run, and no test covers any sync behavior. Closing this out is chunk 0 of Phase 2.
- **Phase 2 (symmetric sync, delete/rename, LWW)** — in progress. Dependency setup is done; implementation has not started.

The real design lives in `docs/`, which is the most important context in this repo:

- `docs/01-overview.md` — goals, explicit non-goals, success criteria.
- `docs/02-plan.md` — the 10-phase roadmap. **This is the source of truth for what to build next.** Each phase has a "Done when" criterion and a status marker; corrections from the 2026-08-11 review are inlined per phase.
- `docs/03-resources.md` — curated reading per phase (papers, reference implementations).
- `docs/04-implementation-notes.md` — cross-cutting technical decisions (concurrency model, library choices, event coalescing). Read before picking up coding.
- `docs/05-review-2026-08-11.md` — historical review of the pre-Phase-2 codebase. Superseded; kept for reference.
- `docs/phase-2/` — **the active work.** `README.md` is the index; `decisions.md` holds numbered decisions cited throughout; `chunk-0` … `chunk-6` are the implementable units, each with spec, implementation guidance, and test criteria.

When implementing a feature, check which phase it belongs to in `docs/02-plan.md` and respect that phase's scope — the plan deliberately defers things (e.g. real CRDT merge until Phase 4) to keep each milestone small. Within Phase 2, respect the chunk boundaries for the same reason: chunk 3 deliberately excludes the watcher so that chunk 4's loopback storm is unambiguous.

## Hard constraints from the design (don't violate these)

- **No consensus algorithms.** No Raft, no Paxos, no quorums. Convergence comes from CRDT math, not coordination. This is intentional.
- **Don't roll your own crypto.** Phase 8 uses a vetted Noise library (`noise-java` on the Scala path).
- **Property-based testing is non-negotiable for the CRDT merge (Phase 4).** The merge function must be proven commutative, associative, and idempotent via property tests — not example tests.
- Stack is fixed: **Scala 3 + (cats-effect or Pekko)**. Don't switch languages mid-project.
- **Conflict logic goes in a pure core; IO stays in a thin shell.** Phase 2 decisions 8, 10, and 11. Everything interesting is a pure-function test; if logic leaks into the socket or watch loops, the tests get slow and vague.

## Commands

```bash
sbt compile        # compile
sbt test           # run all tests
sbt console        # Scala 3 REPL

# Phase 1 demo (currently broken — see docs/phase-2/chunk-0-close-phase-1.md):
sbt 'run receive --dir dirB --port 9000'
sbt 'run send --dir dirA --host localhost --port 9000'

# Run a single test suite or test:
sbt 'testOnly MySuite'
sbt 'testOnly MySuite -- --tests=example'   # munit name filter
```

`sbt` needs write access to `~/.sbt/boot`. Under a restrictive sandbox it fails with
`sbt.boot.lock (Operation not permitted)` — that's the sandbox, not the build.

## Tech

- Scala 3.8.4, sbt. Sources under `src/main/scala`, tests under `src/test/scala`.
- Dependencies in `build.sbt`: scallop (CLI), scala-logging + logback (logging), directory-watcher-better-files (native FSEvents, recursive watch), cats-effect + fs2-core (Phase 2 concurrency), munit + munit-cats-effect (test).