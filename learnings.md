# Learnings

Short, searchable notes tied to code in this repository.

## Quick index

| Area | Learning | Recall and search terms | Code |
| --- | --- | --- | --- |
| Java concurrency | [Daemon threads](#daemon-threads-do-not-keep-the-jvm-alive) | `Thread`, `setDaemon`, JVM shutdown, blocking receiver, test | [`MainTestSuite.scala:10`](src/test/scala/MainTestSuite.scala#L10) |
| Filesystem security | [Confine untrusted paths to a root](#confine-untrusted-paths-to-a-root) | path traversal, containment, `resolve`, `normalize`, `startsWith`, symlink | [`Receiver.scala:24`](src/main/scala/Receiver.scala#L24) |

## Java concurrency

### Daemon threads do not keep the JVM alive

`thread.setDaemon(true)` lets the JVM exit when only daemon threads remain.

**Why here:** It prevents the blocking `Receiver.run` thread from keeping the test JVM alive.

**Caveats:**

- It does not stop or interrupt the thread; JVM shutdown may end it without cleanup.
- Call it before `thread.start()`; calling it after start throws `IllegalThreadStateException`.
- Prefer explicit shutdown and `join()` when cleanup or deterministic completion matters.

**Code:** [`src/test/scala/MainTestSuite.scala:10`](src/test/scala/MainTestSuite.scala#L10)

## Filesystem security

### Confine untrusted paths to a root

A path received from a peer is untrusted input. `root.resolve(receivedPath)` does not guarantee that
the result stays under `root`: an absolute path replaces the root, while `..` can escape it.

For lexical confinement:

1. Convert the configured root to an absolute, normalized path.
2. Reject an absolute received path.
3. Resolve the received path against the root and normalize the result.
4. Require the result to start with the normalized root before performing filesystem IO.

**Why here:** `Receiver.run` currently resolves a wire path and writes it without a containment
check. A malicious or malformed peer could therefore overwrite another writable file.

**Caveats:**

- Lexical containment does not stop an existing symlink inside the root from pointing outside it.
  Refuse symlink traversal or validate path components when the threat model includes local changes.
- Perform the check before creating directories, writing bytes, or changing metadata.
- Path confinement does not replace peer authentication or frame-size validation.

**Code:** [`src/main/scala/Receiver.scala:24`](src/main/scala/Receiver.scala#L24)
