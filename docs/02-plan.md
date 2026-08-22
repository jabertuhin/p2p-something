# P2P File Sync — Plan

Phased breakdown. Each phase is a meaningful milestone: working software, clear "done" criterion, blog-post-shaped scope. Estimated effort assumes part-time pace (a few weekends + scattered evenings).

> **Heuristic for picking up after a break:** find the last phase marked done, open the corresponding section here, re-read the "Done when" line. That's where you are.

---

## Phase 0 — Scaffolding (1 weekend) — ✅ done

**Goal:** working repo, build, test loop. No domain code yet.

**Stories:**
- Pick stack and initialize project (Scala/SBT, Go module, or Cargo crate).
- Set up logging (structured JSON or pretty console — pick one, stick with it).
- Set up a test framework and write one trivial test that runs in CI.
- Decide on a config format (HOCON, YAML, TOML — whatever you like).
- Write a `README.md` stub and commit early.

**Done when:** `make test` (or equivalent) runs green from a fresh clone.

**Learning:** none, really. This is just hygiene. Don't skip it.

---

## Phase 1 — Toy sync, local, unidirectional (1 weekend) — ⚠️ code written, not verified

**Goal:** edit a file in directory A, see the bytes appear in directory B. Both processes on localhost.

**Stories:**
- Spawn a TCP listener on a fixed port. Accept one peer connection.
- Watch directory A for file changes (inotify/fsnotify, or polling for simplicity at first).
- When a file changes, send `(path, bytes, mtime)` over the wire.
- On receive, write the bytes to the corresponding path in directory B.
- Handle create, modify. Skip delete and rename for now.

**Done when:** `echo "hello" > dirA/test.txt` results in `dirA/test.txt` and `dirB/test.txt` having identical contents.

**Learning:** filesystem watch APIs are weirder than they look (coalescing, atomic-write tricks editors use, partial reads). Write protocols are uglier than they look.

> **Status:** `Sender`, `Receiver`, and `Protocol` exist, but the CLI's send branch reads the *receive* subcommand's options, so the documented demo cannot run, and no test covers any of it. Closing this out is [`phase-2/chunk-0-close-phase-1.md`](phase-2/chunk-0-close-phase-1.md).

> **Correction (2026-08-11 review):** `Phase 2 — Bidirectional` also marks the point where Phase 1's status changes from "implemented" to "verified". Don't build symmetric sync on unverified one-way sync — you will not be able to tell a new bug from an old one.

---

## Phase 2 — Bidirectional + delete/rename + last-writer-wins (1–2 weekends)

**Goal:** both peers send and receive. The sync is symmetric. Conflicts exist but are handled with last-writer-wins for now.

**Stories:**
- Both processes both watch and send.
- Add `delete` and `rename` to the wire protocol.
- Tag every event with `(originator_id, wall_clock_timestamp)`.
- On receive of a conflicting write, pick the later timestamp.
- Add a guard against infinite loops (don't re-emit events for changes you just applied).

**Done when:** Two-way sync works for create/modify/delete/rename. Simultaneous edits to the same file resolve to one of the two versions deterministically (even though one is lost).

**Learning:** the loopback problem (your own writes triggering re-broadcasts) is the first real distributed systems bug you'll hit. Wall clocks lie — even on the same machine if you're using mtime from disk.

> **In progress.** Phase 2 is broken into seven implementable chunks in [`phase-2/`](phase-2/) — start at [`phase-2/README.md`](phase-2/README.md). Decisions are logged in [`phase-2/decisions.md`](phase-2/decisions.md). Chunk 0 closes out Phase 1, which a 2026-08-11 review found *implemented but not verified*.

---

## Phase 3 — Vector clocks and conflict detection (1 weekend)

**Goal:** detect concurrent edits properly. You won't resolve them yet — just identify them and write conflicts to a log.

**Stories:**
- Each peer has a stable node ID (UUID, generated on first run, persisted).
- Maintain a vector clock: `Map[NodeId, Long]`. Increment your slot on every local write.
- Tag every outbound event with the vector clock.
- On receive, compare incoming clock with local: `before`, `after`, or `concurrent`.
- For `concurrent`, log it (don't resolve yet). For `before`, drop the message (already seen). For `after`, apply.

**Done when:** Two peers go offline, both edit the same file, come back online — your program prints `CONFLICT detected on path/foo.txt`. No automatic resolution required yet.

**Learning:** this is the moment "happens-before" stops being a textbook concept. Vector clocks grow with the number of peers — note this; you'll care later.

> **Correction (2026-08-11 review):** attach causality to a **path/version**, not to one process-wide clock. Compare an incoming clock against the stored clock *for that same path*. A single global clock per peer makes edits to unrelated files look concurrent, and you'll spend a weekend debugging phantom conflicts.

---

## Phase 4 — First CRDT: file-level LWW-Register with proper merge (1–2 weekends)

**Goal:** replace ad-hoc last-writer-wins with a real CRDT. Start simple — treat each file as a whole-file Last-Writer-Wins Register with vector-clock-based tiebreak.

**Stories:**
- Define a `FileState` type with `(content_hash, vector_clock, last_writer_id)`.
- Define `merge(a, b)` such that it's commutative, associative, idempotent. Prove this with property-based tests (ScalaCheck / quickcheck-style).
- Replace your Phase 3 conflict handling with the merge function.
- For ties (truly concurrent writes), use a deterministic tiebreak (e.g., higher node ID wins, or lexicographic content hash).
- Property test: feed N random sequences of operations in random orders to N peers, then assert all peers converge to the same state.

**Done when:** Property tests pass with 1000+ random scenarios. Concurrent edits resolve deterministically and predictably.

**Learning:** *this is the heart of the project*. CRDTs feel like magic until you've written one. The property-based testing is non-negotiable — it's the only way to be confident your merge is actually associative.

> **Correction (2026-08-11 review):** separate *convergence* from *conflict preservation*. An LWW-Register converges, but it silently discards one concurrent value — which directly contradicts success criterion 2 in [`01-overview.md`](01-overview.md) ("concurrent edits from both ends survive — no silent data loss, no last-writer-wins"). Keep the LWW-Register as the learning milestone, then add a second exercise: a small multi-value register, or conflict copies (`foo.txt` + `foo.sync-conflict-<peer>.txt`, as Syncthing does). Only the second one satisfies the stated goal.

---

## Phase 5 — Persistence and recovery (1 weekend)

**Goal:** restart without losing state. Currently a restart loses all knowledge of vector clocks and file history.

**Stories:**
- Add a local store (SQLite is fine — you're already exploring it for the PKB project) for: `(path → FileState)` and the local vector clock.
- Write a small WAL of incoming operations before applying them.
- On startup, replay any unapplied WAL entries.
- Reconnect to peers and exchange vector clocks; pull anything you're behind on.

**Done when:** kill -9 both peers mid-sync, restart them, they finish syncing correctly.

**Learning:** persistence forces you to confront ordering and atomicity. The "apply to disk vs update clock" race is real.

---

## Phase 6 — Anti-entropy: catching up after disconnection (1 weekend)

**Goal:** two peers that have been disconnected for hours/days reconcile efficiently when they reconnect.

**Stories:**
- On reconnect, exchange vector clocks first.
- Each side computes the delta: "what does the peer not have yet?"
- Send only the missing operations.
- Add a small "what files do we both have, with what hashes?" exchange to catch silent divergence (paranoia mode).

**Done when:** Disconnect peer B for an hour, make 50 changes on peer A, reconnect — peer B catches up in one batch without re-sending unchanged files.

**Learning:** anti-entropy is what makes eventual consistency *eventual* and not "hope-fully-consistent". It's also where most real bugs in production CRDT systems hide.

> **Correction (2026-08-11 review):** Phases 5–6 as written mix two different models — a state-based CRDT (merge whole states) and an operation-delta protocol (ship the ops a peer is missing). Pick deliberately, and start with the simpler one: **full canonical manifest exchange plus state merge**. Both peers send "here is every path with its version", diff, and pull. It's O(files) per reconnect and obviously correct. Only add addressable operations `(originNodeId, counter)` afterwards, if op deltas still look worth the bookkeeping.

---

## Phase 6B — Merkle reconciliation (1 weekend, optional)

**Goal:** stop sending the full manifest on every reconnect.

**Stories:**
- Build a Merkle tree over the path→version manifest.
- On reconnect, exchange root hashes; descend only into subtrees that differ.
- Keep the Phase 6 full-manifest implementation as the correctness oracle — run both, assert they agree.

**Done when:** two peers with 10,000 identical files and one difference reconcile by exchanging a handful of hashes instead of the whole manifest.

**Learning:** this is the trick behind Dynamo, Cassandra, and every anti-entropy repair you'll ever read about. It is also where "my hash tree says we agree but we don't" bugs live — hence the oracle.

---

## Phase 6C — Block-level transfer (optional, revisit)

Sub-file deltas: send only the changed bytes of a changed file. See **Parked optimization** at the bottom of this file for the full survey (rsync, Dropbox, Syncthing BEP).

**Sequencing note:** try fixed-size blocks first, then compare against rsync's rolling checksum. Chunking must sit strictly *below* file-version merge semantics — it changes how content travels, not which version wins. If it starts leaking into the merge, back it out.

---

## Phase 7 — Multi-peer support and gossip discovery (2 weekends)

**Goal:** support 3+ peers. Add SWIM-style gossip so a new peer only needs to know one existing peer to join the cluster.

**Stories:**
- Generalize from "the peer" to "a peer set". Maintain a membership list.
- Implement basic SWIM: periodic ping to random peer, indirect ping via others on timeout, suspicion → dead state machine.
- Gossip the membership list: each ping piggybacks "here are peers I know about".
- A new node bootstraps by being told one peer's address; it learns the rest.
- Test with 5 local processes; kill some, watch the others detect and remove them.

**Done when:** Start node E knowing only node A's address; within 30 seconds E knows about B, C, D and starts syncing with them.

**Learning:** failure detection is harder than it looks (false positives are inevitable; the suspicion state buys you tolerance). Gossip is probabilistic — embrace it.

> **Correction (2026-08-11 review):** separate *replication* from *membership*. First prove replication works across **three statically configured peers** — that alone breaks assumptions baked in by two-peer code (the single socket of Phase 2 decision 6, for one). Only then add SWIM. Also note what SWIM does *not* do: it detects failures and disseminates membership, it does **not** discover the first bootstrap peer. That address still has to come from config or the command line.

---

## Phase 8 — Encrypted transport with Noise (1–2 weekends)

**Goal:** replace plaintext TCP/UDP with Noise-encrypted channels. Peers identify each other by static public key.

**Stories:**
- Generate a long-term keypair on first run; persist it.
- Use the `XX` handshake pattern (mutual auth, both sides learn each other's static key during handshake).
- Use a vetted library — do **not** roll your own crypto. `noise-java`, `snow` (Go), or `snow` (Rust).
- Add a config option: list of trusted peer public keys. Refuse handshakes from unknown keys.
- All Phase 1–7 functionality should still work, just over an encrypted channel.

**Done when:** Run wireshark on the loopback during a sync — see only encrypted noise (pun intended), no plaintext file contents.

**Learning:** Noise handshake patterns are a small but elegant DSL. Understanding why `XX` exists alongside `IK` and `NK` will teach you more about authentication than reading TLS RFCs.

---

## Phase 9 — NAT traversal (2–3 weekends)

**Goal:** two peers on different home networks (both behind NAT) can establish a direct connection. This is the showstopper feature.

**Stories:**
- Stand up a small "rendezvous server" on a VPS (a Hetzner / DigitalOcean box). Its only job is to relay introduction messages between peers and tell each peer its public address.
- Implement the STUN-like protocol on the rendezvous: peer connects, server responds with `(public_ip, public_port)`.
- When peer A wants to connect to peer B, both contact the rendezvous, exchange public addresses, then both fire UDP packets at each other simultaneously (hole-punching).
- Switch the data channel from TCP to **UDP** (necessary for hole-punching). Layer Noise over UDP (Noise has a `NoisePSK` and `IK` variants useful here, but vanilla `XX` over a UDP framing layer is fine for learning).
- Implement a TURN fallback: if hole-punching fails (symmetric NAT), relay traffic through the rendezvous server. Mark this clearly as "slow path".

**Done when:** Your home machine in Dhaka and a friend's machine in another country sync directly, no relay. Verify the path with `tcpdump` showing peer-to-peer traffic.

**Learning:** **this is the most networking you will ever learn in one project.** NAT types, ICE candidates, the actual on-the-wire reality of consumer internet. Read Tailscale's "How NAT traversal works" blog before starting this phase.

> **Correction (2026-08-11 review):** this phase bundles two independent hard problems — treat them separately or you will not know which one is failing.
>
> 1. **NAT traversal.** UDP is not required for every form of hole punching; TCP hole punching exists and simultaneous-open works through some NATs. Do the rendezvous + address-discovery experiments first and find out what your actual NAT does.
> 2. **Reliable transport.** If you *do* move to UDP, note that Noise provides confidentiality, integrity, and authentication — it provides **no** ordering, retransmission, congestion control, or fragmentation. Everything TCP was giving you for free becomes yours to build. Make "do we need reliable UDP, or can we keep TCP?" an explicit, separate decision rather than a consequence of choosing hole punching.

---

## Phase 10 — Polish, docs, and write up (ongoing)

**Goal:** something you'd be okay showing publicly.

**Stories:**
- CLI: `mysync init`, `mysync add-peer <pubkey> <address>`, `mysync status`, `mysync logs`.
- Structured logging with levels. Prometheus metrics endpoint (peer count, files in sync, conflicts resolved, bytes sent).
- A `docs/` folder with architecture notes (this very plan, plus diagrams).
- Blog post series — one per phase. The plan itself is most of the outline.
- Tag a `v0.1.0` release.

**Done when:** you'd link this on your LinkedIn without wincing.

---

## Parked optimization — transfer efficiency (revisit later)

**Problem:** as of Phase 1, every `ENTRY_MODIFY` event re-sends the *entire* file (`Files.readAllBytes` → one `Frame`). A one-byte edit to a 1 GB file re-sends 1 GB, the whole file is buffered in memory on both ends, and editor save patterns fire multiple modify events so a single logical edit can be sent several times.

**What the roadmap already covers (whole-file granularity):**
- Phase 4's `content_hash` enables skip-if-unchanged — don't send a file whose hash matches what the peer has.
- Phase 6 anti-entropy sends only the *operations* a peer is missing, "without re-sending unchanged files."

**What is NOT yet planned (sub-file granularity):** sending only the *changed bytes within* a changed file. Worth a dedicated investigation once the CRDT core (Phase 4–6) is stable. Study how production sync services handle this and decide what fits our whole-file LWW-Register model (mechanisms below verified against the cited sources, 2026-06):

- **rsync algorithm** — the *receiver* splits its existing copy into fixed-size blocks and sends a (weak rolling Adler-32, strong hash) pair per block; the sender rolls the weak checksum byte-by-byte over its version, confirms weak matches with the strong hash, and transmits only the unmatched literal regions plus references to matched blocks. The classic receiver-drives baseline. (MD4 originally; MD5 since protocol v30 / rsync 3.0.0.) — Tridgell & Mackerras, "The rsync algorithm" (TR-CS-96-05): https://rsync.samba.org/tech_report/
- **Dropbox** — fixed-size **4 MB** blocks (last block smaller), **not** content-defined chunking. Each block is content-addressed by its **SHA-256** and stored as an immutable, deduplicated blob; a file is the ordered list of block hashes, so a save re-uploads only the blocks whose hash changed. — https://dropbox.tech/infrastructure/inside-lan-sync and content-hash spec https://www.dropbox.com/developers/reference/content-hash *(Note: many third-party "system design" posts wrongly attribute content-defined chunking to Dropbox — it's fixed 4 MB.)*
- **Google Drive** — **not** block-level delta. Changing content replaces the whole file (PATCH/PUT to the `fileId`, creating a new revision); resumable uploads may split the stream into 256 KB-multiple chunks purely for transfer reliability, not to diff changed bytes. The cleanest "whole-file replace" contrast for our note. — https://developers.google.com/workspace/drive/api/guides/manage-uploads
- **Syncthing** (our north star) — Block Exchange Protocol v1. Files split into blocks (variable **128 KiB–16 MiB**, power-of-two, constant within a file). Devices exchange an **Index** of per-block hashes, build a global model (newest version via version vectors), then **pull** only missing/stale blocks via **Request** messages (one block each, with expected hash). A request can be satisfied locally by copying any existing block with the same hash (block reuse), avoiding the network entirely. Closest design to what we'd build. — https://docs.syncthing.net/specs/bep-v1.html

**Open question for us:** sub-file deltas complicate the merge — our CRDT treats a file as one LWW-Register, so block-level sync is a *transport* optimization that must stay invisible to the merge layer. Decide whether the complexity is worth it for a learning project or whether content-hash skip (Phase 4) is "good enough."

---

## Rough timeline

If you do 1–2 phases per month (realistic with kids, work, and the rest of your curriculum), this is a **6–9 month project**. Don't promise yourself faster — promise yourself it's okay to stop after Phase 5 if life gets in the way. Phase 4 + 5 alone is a complete, defensible learning project.

## Suggested stopping points

- **Floor (minimum value):** through Phase 4. You've built a real CRDT and understand it cold.
- **Solid mid-point:** through Phase 7. You've got CRDTs, gossip, and multi-peer — basically a primitive Syncthing minus encryption and NAT.
- **Full vision:** through Phase 9. You can demo it actually working between two real homes.
- **Showable artifact:** Phase 10. Optional but high-leverage given your writing habit.
