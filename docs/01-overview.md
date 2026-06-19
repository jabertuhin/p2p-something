# P2P File Sync — Project Overview

> Working name. Rename whenever something better comes to mind (`drift`, `confluence`, `eddy`, whatever).

## What this is

A peer-to-peer file synchronization tool, built from scratch as a learning project. Two or more nodes watch a directory each; changes propagate between them without any central server. Eventually consistent, conflict-free, encrypted, and able to work between devices on different home/office networks behind NAT.

Think of it as building a tiny Syncthing — not to replace Syncthing, but to understand every layer of how something like it works by writing each layer yourself.

## What you're actually here to learn

Each piece of this project teaches one thing that doesn't show up in your day-to-day data engineering work:

| Concept | Why it matters |
|---|---|
| **CRDTs** (Conflict-free Replicated Data Types) | A fundamentally different consistency model than the leader-follower world of DDIA. No consensus, no leader, no quorum — convergence via math instead of coordination. |
| **Vector clocks** | The mechanism for detecting causality and concurrency without trusting wall clocks. Foundation for understanding any eventually-consistent system. |
| **Gossip protocols (SWIM)** | How nodes discover each other and detect failures without a central registry. The pattern behind Cassandra, Consul, Bitcoin. |
| **NAT traversal (STUN, hole-punching)** | How the actual consumer internet works between devices that don't have public IPs. Cloud engineering hides this completely. |
| **Noise protocol framework** | Lightweight authenticated encryption without certificate authorities. Same machinery as WireGuard and WhatsApp. |
| **Filesystem watching, wire protocols, raw sockets** | Below-the-abstraction-layer plumbing that managed cloud services normally hide. |

## What this is *not*

- **Not a Syncthing replacement.** Syncthing has a decade of edge-case handling. This project is for learning, not production.
- **Not a consensus exercise.** No Raft, no Paxos. The whole point is that CRDTs let you sidestep consensus entirely. If you want consensus, do a separate Raft project.
- **Not blockchain anything.** No coins, no proof-of-anything, no distributed ledger framing. This is plain old eventual consistency.
- **Not a CRUD app with a sync feature.** Sync *is* the product. Don't get distracted building a UI before the sync works.

## Tech stack

Default choice: **Scala 3 + cats-effect** (or **Pekko** if you want actors for the gossip layer). Reasons: it's your daily language, the JVM has decent NIO primitives for UDP/TCP, and you'll get more reps in your main language.

Honest alternatives:

- **Go** — by far the most idiomatic choice for this domain. Tailscale, Syncthing, and most modern networking tools are Go. The standard library has everything you need (net, crypto, fsnotify). If you've been meaning to learn Go properly, this is the project.
- **Rust** — even more idiomatic for systems-level networking, and the Noise/Quinn/Tokio ecosystem is excellent. Steeper learning curve; you'd be learning Rust and CRDTs simultaneously, which might slow the project.

Pick one and don't switch mid-project. If unsure: **Scala for momentum, Go for transfer value to the rest of the systems world**.

## Success criteria

The project is "done enough" when:

1. You can run the binary on two devices on different home networks (e.g., your machine in Dhaka and a VPS in Europe), point each at a directory, and watch them stay in sync.
2. Concurrent edits to the same file from both ends survive — no silent data loss, no last-writer-wins.
3. All traffic is encrypted and peers are mutually authenticated by public key.
4. The code is something you'd be comfortable showing in a blog post series.

Each phase in the plan has its own "done" criterion so you can stop at any maturity level without it feeling unfinished.

## Why this project specifically (for you)

- **Pivot away from your current trajectory.** Nothing in your memory of recent work touches CRDTs or raw networking — this is a deliberate departure from streaming/data engineering, not another flavor of it.
- **Toy-first gradient.** The Phase 1 toy is a weekend of work. Every subsequent phase is meaningful on its own and you can stop whenever life gets busy.
- **Strong artifact for writing.** Each layer (CRDTs, gossip, NAT traversal, Noise) is its own blog post. The whole series could easily fill a quarter of Substack output.
- **Staff-level signals.** Implementing a CRDT and explaining NAT traversal in your own words are both things that mark genuinely senior systems engineers.
