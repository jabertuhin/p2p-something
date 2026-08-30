import com.typesafe.scalalogging.StrictLogging

import java.io.{BufferedOutputStream, DataOutputStream}
import java.net.Socket
import java.nio.file.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object Sender extends StrictLogging{
  def run(dir: String, host: String, port: Int): Unit =
    val directory = Paths.get(dir)
    val watcher = FileSystems.getDefault.newWatchService()
    directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)

    val socket = new Socket(host, port)
    val out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream))

    try {
      while true do
        val key = watcher.take()
        for (ev <- key.pollEvents().asScala) {
          val relativePath = ev.context().asInstanceOf[Path]
          val fullPath = directory.resolve(relativePath)

          if (Files.isRegularFile(fullPath)){
            val bytes = Files.readAllBytes(fullPath)
            val mtime = Files.getLastModifiedTime(fullPath).toMillis
            Protocol.write(out, Frame(relativePath.toString, mtime, bytes))
            logger.info(s"Path: ${fullPath} | Byte Size: ${bytes.size}")
          }
        }
        key.reset()
    } catch
      case NonFatal(e) =>
        logger.error("Sender failed while transferring a file.", e)
    finally{
      socket.close()
      watcher.close()
    }
}
