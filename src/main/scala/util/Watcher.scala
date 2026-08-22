package util

import com.typesafe.scalalogging.StrictLogging
import better.files.*
import better.files.File.Monitor
import io.methvin.better.files.*

import java.nio.file.{Path, StandardWatchEventKinds, WatchEvent}

object Watcher extends StrictLogging {
  /*
   Benefits you get:

  - Recursion for free. Plain WatchService only watches the top-level dir you register. RecursiveFileMonitor walks the tree and registers a watch on every subdirectory, and
  re-registers when new subdirectories appear. For a sync tool, you need every file in the tree, not just the top level — this is the main reason to use it.
  - Native OS backends. It uses FSEvents on macOS, inotify on Linux, ReadDirectoryChangesW on Windows, instead of Java's slow poll-based fallback on macOS.
  - One unified event callback (onEvent) instead of you writing the watch-key-register-poll loop yourself.
  */
  def getWatcher(filePath: File): Monitor = {
    val watcher: RecursiveFileMonitor = new RecursiveFileMonitor(filePath) {
      override def onEvent(eventType: WatchEvent.Kind[Path], file: File, count: Int): Unit = eventType match {
        case StandardWatchEventKinds.ENTRY_CREATE => println(s"$file got created")
        case StandardWatchEventKinds.ENTRY_MODIFY => println(s"$file got modified $count")
        case StandardWatchEventKinds.ENTRY_DELETE => println(s"$file got deleted")
      }
    }
    watcher
  }
}
