package io.quartz.util

import java.nio.file.{FileSystems, Path}

import io.quartz.QuartzH2Server
import java.nio.file.{WatchService, WatchEvent, Files}
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchEvent.Kind
import cats.effect.IO
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.collection.mutable.Buffer
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import java.io.File

import org.typelevel.log4cats.Logger
import ch.qos.logback.classic.Level
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._
import cats.implicits._

class FileEventListener(folderPath: String, proc: (File, Kind[Path]) => IO[Unit]) {

  private val watcher = FileSystems.getDefault().newWatchService();
  private val rootFolder = FileSystems.getDefault().getPath(folderPath);

  Files.walkFileTree(
    rootFolder,
    new java.nio.file.SimpleFileVisitor[Path] {

      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
        dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        FileVisitResult.CONTINUE;
      }

    }
  )

  /** Start listening from root folder.
    * @return
    */
  def listen =
    Logger[IO].info(s"Start listening on file sytem events from ${rootFolder.toString()}") >> worker.foreverM.start

  def close : IO[Unit] = IO(watcher.close())

  private def worker: IO[Unit] = {
    IO.blocking(watcher.take())
      .bracket(key =>
        (for {
          events <- IO(key.pollEvents().asScala.toSeq)
          r <- events.traverse { event =>
            for {
              ev <- IO(event.asInstanceOf[WatchEvent[Path]])

              folder <- IO(key.watchable().asInstanceOf[Path])
              kind <- IO(ev.kind())
              context <- IO(ev.context())

              opt_context: Option[Path] <-
                if (kind == ENTRY_MODIFY || kind == ENTRY_CREATE || kind == ENTRY_DELETE)
                  IO(Some(folder.resolve(context)))
                else IO(None)
              path_opt <- IO(for {
                filename <- opt_context

              } yield (filename))
            } yield ((path_opt, kind))
          }

          files <- IO(r.foldLeft(List.empty[(java.io.File, Kind[Path])])((prev, seg) => {
            seg._1 match {
              case Some(s) => prev.::(new java.io.File(s.toString()), seg._2)
              case None    => prev
            }
          }))
          // if new folders, adding a listener on it
          _ <- IO(files.foreach(file => {
            if (file._1.isDirectory() && file._2 == ENTRY_CREATE) {
              file._1.toPath().register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            }

          }))
          _ <- files.traverse(proc.tupled(_))
          _ <- IO(key.reset())
        } yield ()).handleError(e => Logger[IO].error("File System Listener: " + e.toString()))
      )(key => IO(key.reset()))
      .void
  }

}
