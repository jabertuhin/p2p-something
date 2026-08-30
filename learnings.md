# Learnings

Short, searchable notes tied to code in this repository.

## Quick index

| Area | Learning | Recall and search terms | Code |
| --- | --- | --- | --- |
| Java concurrency | [Daemon threads](#daemon-threads-do-not-keep-the-jvm-alive) | `Thread`, `setDaemon`, JVM shutdown, blocking receiver, test | [`MainTestSuite.scala:10`](src/test/scala/MainTestSuite.scala#L10) |

## Java concurrency

### Daemon threads do not keep the JVM alive

`thread.setDaemon(true)` lets the JVM exit when only daemon threads remain.

**Why here:** It prevents the blocking `Receiver.run` thread from keeping the test JVM alive.

**Caveats:**

- It does not stop or interrupt the thread; JVM shutdown may end it without cleanup.
- Call it before `thread.start()`; calling it after start throws `IllegalThreadStateException`.
- Prefer explicit shutdown and `join()` when cleanup or deterministic completion matters.

**Code:** [`src/test/scala/MainTestSuite.scala:10`](src/test/scala/MainTestSuite.scala#L10)
