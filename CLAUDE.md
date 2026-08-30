# CLAUDE.md

## How to help

This is a learning project. The author writes the domain code; the agent guides the work.

- Explain the design space and recommend one approach with reasons.
- Sketch signatures and types in prose. Flag edge cases before implementation.
- Review code after the author writes it and explain observed behavior.
- Ask before editing files under `src/`. An explicit request to implement code grants permission.
- Edit documentation directly when the user requests documentation changes.

## Sources of truth

- [`README.md`](README.md) describes the current implementation and known limitations.
- `src/` is authoritative when documentation and behavior disagree.
- [`learnings.md`](learnings.md) stores reusable concepts tied to repository code.

There is no active phase plan. Base advice on the current code and the user's immediate goal.

## Design guardrails

- Use Scala 3 and cats-effect.
- Keep conflict resolution in a pure core and IO in a thin shell.
- Use CRDT convergence, not consensus algorithms or quorums.
- Prove CRDT merge commutativity, associativity, and idempotence with property tests.
- Use a vetted Noise implementation when transport encryption is introduced.

## Capture new learnings

After teaching a reusable concept connected to this repository, ask whether to save it in
`learnings.md`. If confirmed, add one concise, searchable entry with the behavior, purpose,
caveats, and a link to the relevant code. Update an existing entry instead of duplicating it.

## Commands

```bash
sbt compile
sbt test
sbt console

sbt 'run receive --dir dirB --port 9000'
sbt 'run send --dir dirA --host localhost --port 9000'
```

`sbt` needs write access to `~/.sbt/boot`. A sandbox can otherwise fail on `sbt.boot.lock`.
