# P2P File Sync — Resources

Curated. Listed roughly in the order you'd want to consume them — read the first item in each section before starting the corresponding phase.

## Foundational reading (before Phase 0)

- **DDIA, Chapter 5** — Replication. Re-read the "Leaderless Replication" section. This project is the leaderless world taken to its logical extreme.
- **Martin Kleppmann, "Conflict-free replicated data types" — YouTube talk** (~40 min). The cleanest one-shot introduction to CRDTs from a working researcher who also writes well. Search: `Martin Kleppmann CRDT talk`.

## Scala language / effect system

- **"Hands-on Scala Programming" (Li Haoyi)** — project-driven, pragmatic, imperative-friendly. Uses Li Haoyi's own libs (`os-lib`, `requests`, `castor` actors); deliberately *not* a functional-effect-system book — plain `Future`/exceptions. Great fit for the **Phase 1 plain-Scala** approach. **Has a file-synchronizer chapter** (watch a directory, send changes over the wire) that maps almost exactly onto Phase 1 — read it early as a reference implementation. Scala-2-era text; concepts transfer cleanly to Scala 3.
- **"Essential Effects" (Adam Rosien)** + the **cats-effect & fs2 official docs** — the standard pairing for the **Phase 2+ cats-effect/fs2** half of the project. Hands-on Scala does *not* cover this ground.

## CRDTs (before Phase 4)

### Papers
- **Shapiro, Preguiça, Baquero, Zawirski — "Conflict-free Replicated Data Types" (2011)** — the canonical reference. Skim the taxonomy (CmRDT vs CvRDT); read the LWW-Register and OR-Set sections carefully. INRIA tech report, freely available.
- **"A comprehensive study of Convergent and Commutative Replicated Data Types"** by the same authors — the longer version of the above with proofs. Worth keeping open as a reference.

### Books
- **"Crdt.tech"** — a curated website (no, really, that's the domain) maintaining an index of CRDT papers, implementations, and explanations. Bookmark it.

### Implementations to read
- **Automerge** ([github.com/automerge/automerge](https://github.com/automerge/automerge)) — JSON CRDT in Rust/JavaScript. The Rust core is readable. Start here for "what does a real CRDT codebase look like?"
- **Yjs** ([github.com/yjs/yjs](https://github.com/yjs/yjs)) — high-performance CRDT for collaborative editing. The internals are dense but the README and docs explain the design well.

## Vector clocks and causality (before Phase 3)

- **Leslie Lamport, "Time, Clocks, and the Ordering of Events in a Distributed System" (1978)** — short, foundational, surprisingly readable. The happens-before relation comes from here.
- **Wikipedia "Vector clock"** — good enough for the implementation details. Read after Lamport.
- **"Why Logical Clocks are Easy"** by Manuel Bravo, Nuno Diegues — ACM Queue article. Practical perspective.

## Gossip / SWIM (before Phase 7)

- **Das, Gupta, Motivala — "SWIM: Scalable Weakly-consistent Infection-style Process Group Membership Protocol" (2002)** — the original paper. Short (10 pages), clear, implementable directly from the text.
- **HashiCorp's "Memberlist" library** ([github.com/hashicorp/memberlist](https://github.com/hashicorp/memberlist)) — production SWIM implementation in Go. Reference for "how is this done in practice." Used by Consul and Nomad.
- **"Lifeguard: Local Health Awareness for More Accurate Failure Detection"** — HashiCorp's extension to SWIM that reduces false positives. Read after SWIM proper.

## NAT traversal (before Phase 9 — this is the big one)

### Must-read
- **Tailscale, "How NAT traversal works"** — the single best resource on this topic, anywhere. Written by people who actually built a production NAT traversal stack. Go to tailscale.com/blog and search the title.
- **RFC 8489 — STUN** — the protocol itself. Shorter than you'd expect; ICE references this.
- **RFC 8445 — ICE** — the framework that combines STUN, TURN, and hole-punching. Don't read end-to-end; use as reference.

### Background
- **"Peer-to-Peer Communication Across Network Address Translators"** by Ford, Srisuresh, Kegel (2005) — the foundational paper on hole-punching techniques. Explains NAT types (full cone, restricted, port-restricted, symmetric) and which can be punched.
- **Tailscale's "How Tailscale Works"** blog post — broader context for the NAT post above.

### Implementations
- **pion/ice** ([github.com/pion/ice](https://github.com/pion/ice)) — Go implementation of ICE. The pion organization has the most readable WebRTC stack in any language.
- **libnice** — the C reference implementation. Mature, used everywhere, harder to read.

## Noise protocol (before Phase 8)

- **Noise Protocol Framework specification** ([noiseprotocol.org/noise.html](https://noiseprotocol.org/noise.html)) — the spec itself. Read sections 1–7. Note the handshake pattern notation (it looks scary but it's just a DSL).
- **"The Noise Protocol Framework" by Trevor Perrin — Real World Crypto talk** — YouTube. Good intuition for why Noise exists and what it's *not* (it's not TLS).
- **WireGuard whitepaper** by Jason A. Donenfeld — short and beautiful, shows Noise applied in a real system. WireGuard uses the `IKpsk2` pattern. Even if you don't use the same pattern, this is the cleanest applied example.

### Libraries (use, don't reimplement)
- **`snow`** (Rust) — [github.com/mcginty/snow](https://github.com/mcginty/snow)
- **`flynn/noise`** (Go) — [github.com/flynn/noise](https://github.com/flynn/noise)
- **`noise-java`** — [github.com/rweather/noise-java](https://github.com/rweather/noise-java) — for the Scala path

## Reference systems to study (the whole project)

These are not for code-stealing — they're for "how did real engineers solve this problem?" When you're stuck on a design question, find the equivalent in one of these.

- **Syncthing** ([github.com/syncthing/syncthing](https://github.com/syncthing/syncthing)) — the closest project to what you're building. Go. The Block Exchange Protocol spec in their docs is excellent.
- **Tailscale** — proprietary core, but the blog (tailscale.com/blog) is a masterclass in modern P2P networking. Read everything tagged "engineering".
- **WireGuard** — minimal, opinionated, all the design decisions are justified in the whitepaper.
- **IPFS** — wildly more complex than you need but the libp2p stack underneath has separable lessons.

## Tools you'll want installed

- **Wireshark / tshark** — packet capture. You will use this constantly in Phases 1, 8, 9.
- **netcat / `nc`** — for poking at TCP/UDP sockets manually.
- **tcpdump** — Wireshark on the command line. Faster for quick captures.
- **A second machine** — VPS, an old laptop, anything. Needed properly from Phase 9, useful from Phase 7.
- **Property-based testing library** — ScalaCheck (Scala), gopter or rapid (Go), proptest (Rust). Phase 4 requires this.

## Talks worth an evening each

- Martin Kleppmann — "Transactions: myths, surprises and opportunities" (DDIA author, on consistency models)
- Peter Bailis — "Linearizability versus Serializability" (distinguishing consistency guarantees)
- Pat Helland — "Immutability changes everything" (philosophical underpinning of event-based systems, relevant to CRDTs)
- Avery Pennarun (Tailscale) — any Tailscale blog post translated to talk form; check their conference appearances

## When you get stuck

- **Reddit**: r/distributedsystems (low traffic but high signal)
- **DDIA reading group archives** — your own group; lean on it for Phase 4 discussions
- **Hacker News search** for `CRDT`, `NAT traversal`, `SWIM` — surprisingly good archive of practitioner threads
- **The Automerge and Yjs Discord/Slack** — both have active communities of people who *care* about this stuff and answer questions

## Things to deliberately *not* read

- TLS 1.3 RFC. You're using Noise, not TLS. Reading TLS will confuse you.
- Raft / Paxos papers. You're not building consensus. Don't get nerd-sniped.
- Anything about distributed databases (Spanner, Cassandra internals). You're not building one. The Cassandra gossip code is fine to peek at; the rest is a tangent.
