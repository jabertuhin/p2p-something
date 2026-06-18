import com.typesafe.scalalogging.StrictLogging

import java.net.Socket
import java.nio.file.{FileSystem, FileSystems, Path, Paths, StandardWatchEventKinds}

object Sender extends StrictLogging{
  def run(dir: String, host: String, port: Int): Unit =
    val directory = Paths.get(dir)
    val server = new Socket(host, port)

    val watcher = FileSystems.getDefault.newWatchService()
    directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)



}
