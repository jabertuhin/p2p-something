# Chunk 2 — The wire model

**Depends on:** chunk 0. **Unblocks:** chunk 3.
**Size:** a weekend morning. Pure code, no concurrency, no IO.

## Why this chunk exists

A wire protocol is a contract between two programs that will eventually be different versions of
themselves, written by someone who has forgotten what they meant, running on machines that may be
hostile. Phase 1's `Frame(path, mtime, bytes)` is not a contract; it's a struct that happens to get
serialized.

Three concrete deficiencies:

**It can't express a delete.** There's no room in the type. Adding one means either a magic value
(zero-length bytes means delete — now every reader must know that convention) or a new type. The
new type is right, and it's the whole reason this chunk exists before chunk 3.

**It has no identity or ordering information.** `mtime` is in there, but as decision 2 explains at
length, mtime describes the file, not the event. Nothing says *which peer* observed this change or
*when they observed it*, so there's nothing to resolve a conflict with.

**It trusts its input completely.** `read` calls `readInt()` and immediately allocates an array of
that size. A peer that sends `Int.MaxValue` gets you an `OutOfMemoryError`. It then resolves the
received path against your sync root with no validation, so a peer that sends `../../../.ssh/
authorized_keys` writes there. Neither is a theoretical concern in a program whose entire purpose is
accepting data from other machines.

**The framing idea to internalise.** Every message on a stream needs to answer "where do I end?"
before the reader can do anything else. Length-prefixing (what `writeInt(length)` then `write(bytes)`
does) is one of exactly three general answers — the others are delimiters and self-describing
formats. Length-prefixing is fast and simple, and its failure mode is that the length is a promise
the sender might break, deliberately or by accident. Every length-prefixed protocol you will ever
read has a maximum-size check next to the allocation, and now you know why.

## Spec

1. The wire carries a closed set of exactly two event types: **upsert** and **delete** (decision 4).
2. An upsert carries: path, originator id, event timestamp, file mtime, content bytes.
3. A delete carries: path, originator id, event timestamp. No bytes.
4. Every message begins with a **protocol version**, then a **type tag**, so a reader can dispatch
   before consuming anything type-specific.
5. Decoding is **total** with respect to malformed input: it returns a typed failure, never an
   arbitrary exception escaping to the caller.
6. A declared payload length that is negative, or larger than an agreed maximum, is rejected
   **before** any array is allocated.
7. An unrecognised type tag or protocol version is rejected with a diagnosable error.
8. A received path that is absolute, or that escapes the sync root once normalised, is rejected.
9. Two messages written back-to-back to one stream decode as two messages.
10. Round-tripping any valid event yields an equal event.

## Implementation guidance

**The event type.** A sealed hierarchy with two cases. Everything downstream of decoding speaks this
type; `Frame` is deleted, not extended. Put the three common fields (path, originator id, timestamp)
somewhere they're reachable uniformly — a shared trait with abstract members, or a small header case
class both cases hold. Which you pick matters less than the fact that the sync core in chunk 5 needs
`(path, ts, originatorId)` from any event without matching on its type.

Model the path as a *relative* path from the sync root, and consider making that a distinct type
rather than a `String`. A type that can only be constructed through validation is the cheapest way
to make trap 8 unforgettable — every call site that has one knows it's safe, because there's no
other way to have obtained it. This is the same lesson as chunk 0's CLI bug from the other
direction: there, two identically-shaped types let a bug through; here, a distinct type keeps one out.

**The codec.** Two functions over a byte stream: write an event, read an event. Keep the existing
`DataOutputStream`/`DataInputStream` framing — this chunk is about the *model*, and swapping the
serialization mechanism at the same time would confound the two.

Layout, in order: version, tag, then per-case fields. Put version first so that a future format
change is detectable even if the tag numbering changes underneath it.

Make failure a value, not an exception. `Either` with a small error type is the natural Scala shape.
The reason to prefer it here isn't purity for its own sake: the caller in chunk 3 has to decide, per
message, whether a failure is fatal to the connection or skippable, and that decision is much harder
to write against a thrown exception. Note that `readUTF` and `readFully` *will* throw — the codec's
job is to catch at its own boundary and convert.

**A maximum payload constant.** Pick a number, write down why. It bounds one allocation, so it's a
safety property, not a tuning parameter. Something in the tens-of-megabytes range keeps the toy
usable; note in a comment that whole-file transfer is a known Phase 2 limitation and this constant
is where the pain surfaces.

**Path validation** belongs at or immediately behind the codec — before any filesystem call sees the
value. Normalise first, then check containment; checking for the literal string `..` before
normalising is the classic mistake, because `a/../../b` and `%2e%2e` and symlinks all defeat it. The
robust formulation is: resolve against the root, normalise, and verify the result still starts with
the root.

## Test criteria

**Seam: the codec.** Pure, no sockets, no filesystem. Feed it a `ByteArrayInputStream` and read the
result out of a `ByteArrayOutputStream`. These tests should run in single-digit milliseconds.

Round-trip:
- An upsert with ordinary content round-trips to an equal value.
- A delete round-trips to an equal value.
- An upsert with **empty** content round-trips as empty, not absent.
- A path containing non-ASCII characters round-trips intact.
- Two events written back-to-back decode as those two events, in order.

Malformed input — each asserts a *typed failure*, and none throws:
- Truncated payload: the length says 100, the stream ends after 40.
- Truncated header: the stream ends mid-way through the path.
- Negative declared length.
- Declared length above the maximum. Assert specifically that no allocation of that size
  happened — the easiest way is to make the limit small in the test and use a length that would be
  obviously fatal.
- Unknown type tag.
- Unknown protocol version.
- Empty stream.

Adversarial paths:
- Absolute path is rejected.
- `../escape.txt` is rejected.
- `sub/../ok.txt` is *accepted* and normalises to `ok.txt` — rejection must be about escaping the
  root, not about the presence of `..`.
- Path that is empty, or is `.`, is rejected.

One more, worth doing and easy to skip: **an input stream that returns one byte per `read` call**.
Wrap your byte array in a stream that deliberately under-delivers. This is the single most common
real-world protocol bug — code that assumes `read` fills the buffer — and `readFully` exists
precisely to handle it. Writing the test teaches you what `readFully` is *for*, which is more
valuable than the coverage.

## Traps

**Don't validate the path on the send side only.** The check must live on the *receive* side,
because the threat model is a peer you don't control. Validating on send is a nicety; validating on
receive is the security property.

**`readUTF` has a 65,535-byte limit** on the encoded string. Long paths in deeply nested trees can
exceed it, and the failure is a thrown exception, not a truncation. Either accept the limit and
document it, or length-prefix the path yourself.

**Beware making the error type too coarse.** "Malformed" as a single case is easy to write and
useless in chunk 3, where you need to distinguish "this message was bad, skip it" from "the stream
is desynchronised, tear down the connection". After a truncated body you cannot skip — you no longer
know where the next message starts. Distinguishing recoverable from unrecoverable at the codec
boundary saves you a nasty debugging session later.

**Version and tag both being one byte is fine**, but decide whether an unknown *version* is fatal
while an unknown *tag* might be skippable. That's the question below.

## Exit gate

Malformed input fails predictably, without over-allocating and without partially applying anything.
Every valid event round-trips.

## Question to sit with

**What should a peer do when it receives a message with a protocol version or type tag it doesn't
recognise?**

There are three defensible answers — reject the whole connection, skip the message and continue, or
apply what you can understand and ignore the rest — and they lead to genuinely different protocol
designs. Note that "skip and continue" is only *possible* if the framing lets you find the end of a
message you can't interpret, which means the length has to be readable from the generic header
rather than from type-specific fields. That's a design decision you're making right now, in the byte
layout, whether or not you notice it.

Whichever you pick, write it in [`decisions.md`](decisions.md) — Phase 8 changes the wire format and
this will matter.

## Reading

- `java.io.DataInput.readFully`:
  <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/DataInput.html#readFully(byte%5B%5D)>
- Syncthing's Block Exchange Protocol v1 spec — a real, well-documented sync protocol of the shape
  you're building toward: <https://docs.syncthing.net/specs/bep-v1.html>
- Skim RFC 9293 (TCP) §3.1 for framing context: TCP gives you a byte *stream*, with no message
  boundaries at all. Every message boundary in your protocol is one you invented and must maintain.
