import com.typesafe.scalalogging.StrictLogging

import java.io.{BufferedInputStream, DataInputStream, EOFException}
import java.net.ServerSocket
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Paths}

object Receiver extends StrictLogging:
  def run(dir: String, port: Int): Unit =
    val directory = Paths.get(dir)
    val server = new ServerSocket(port)

    val socket = server.accept()
    val in = new DataInputStream(new BufferedInputStream(socket.getInputStream))

    try
      while(true)
        val frame = Protocol.read(in)
        val target = directory.resolve(frame.path)

        Files.createDirectories(target.getParent)
        Files.write(target, frame.bytes)
        Files.setLastModifiedTime(target, FileTime.fromMillis(frame.mtime))
        logger.info(s"{$target} file writing done")
    catch
      case _: EOFException => logger.info("Peer disconnected")
    finally
      logger.info("Shutting down.")
      socket.close()
      server.close()



