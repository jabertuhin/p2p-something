import munit.FunSuite

import java.nio.file.Files
import java.util.concurrent.{CompletableFuture, TimeUnit}

class MainTestSuite extends FunSuite{
  test("Full test"){
    val tempDirA = Files.createTempDirectory("dirA")
    val tempDirB = Files.createTempDirectory("dirB")

    val bound = new CompletableFuture[Integer]()
    val connected = new CompletableFuture[Unit]()

    val threadA = new Thread(() => Receiver.run(
  tempDirA.toString, port = 0,
      p => bound.complete(p),
      () => connected.complete(())))
    threadA.setDaemon(true)
    threadA.start()

    val threadB = new Thread(() => Sender.run(tempDirB.toString, "localhost", bound.get(2, TimeUnit.SECONDS)))
    threadB.setDaemon(true)

//    threadA.start()
    bound.get(2, TimeUnit.SECONDS)
    threadB.start()
    connected.get(2, TimeUnit.SECONDS)

    val expectedContent = "Hello World!"

    Files.writeString(tempDirB.resolve("helloworld.txt"), expectedContent)
    Thread.sleep(5000)
    val actualContent = Files.readString(tempDirA.resolve("helloworld.txt"))

    assertEquals(actualContent, expectedContent)
  }

}
