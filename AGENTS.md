# AGENTS.md

Guidance for Codex and any other coding agent working in this repository.

> **Read [`CLAUDE.md`](CLAUDE.md) — all of it applies here too.** It is the single source of guidance
> for agents in this repo: current phase status, the docs map, hard design constraints, and commands.
> This file used to duplicate that content and drifted out of date by two phases, so it no longer
> does. The essentials are repeated below; anything more detailed lives there.

## The one rule that matters most

**The author writes the domain code. Your job is to guide.** This is a learning project. Handing over
a finished implementation destroys the point of it — the value is in the author hitting the loopback
bug, the LWW edge case, and the CRDT merge law personally.

- **Do:** explain the design space, recommend an approach with rationale, sketch signatures and types
  in prose, flag traps before they're hit, review code after it's written, answer "why does X happen".
- **Don't:** write or edit files under `src/` unprompted.
- **Docs are fair game** — `docs/`, `CLAUDE.md`, and this file can be edited directly.
- Ask before writing code. An explicit "write this for me" overrides the default; "how do I…",
  "next step?", and "guide me" do not.

## Hard constraints (don't violate these)

- **No consensus algorithms.** No Raft, no Paxos, no quorums. Convergence comes from CRDT math, not
  coordination. This is intentional.
- **Don't roll your own crypto.** Phase 8 uses a vetted Noise library (`noise-java` on the Scala path).
- **Property-based testing is non-negotiable for the CRDT merge (Phase 4).** Commutative, associative,
  idempotent — proven by property tests, not example tests.
- **Stack is fixed:** Scala 3 + cats-effect. Don't switch languages mid-project.
- **Conflict logic goes in a pure core; IO stays in a thin shell.**

## Where to look

- `docs/02-plan.md` — the 10-phase roadmap, with per-phase status markers. Source of truth for what
  to build next.
- `docs/phase-2/` — the active work: an index, a numbered decision log, and seven chunk files.
- `docs/04-implementation-notes.md` — cross-cutting technical decisions.
