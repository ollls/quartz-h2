package io.quartz.netio

import java.util.function.Consumer
import java.nio.ByteBuffer
import cats.effect.IO
import cats.effect.std.{Mutex, Queue}
import cats.effect.Ref
import scala.collection.immutable.List
import cats.implicits._
import io.quartz.iouring.{IoUring, IoUringSocket}
import io.quartz.QuartzH2Server
import org.typelevel.log4cats.Logger
import io.quartz.MyLogger._

/** IoUringEntry represents an entry in the IoUringTbl. Each entry contains a Mutex for synchronization, a Ref counter
  * to track usage, and the IoUring instance itself.
  *
  * @param q
  *
  * @param counter
  *   Reference counter to track usage
  * @param ring
  *   IoUring instance
  */

case class IoUringEntry(
    q: Queue[IO, IO[Unit]], // IO[Unit] IO wraped op to execute later.
    cntr: Ref[IO, Int],
    ring: IoUring
) {

  /** Synchronized wrapper for IoUring's queueRead method.
    *
    * @param entry
    *   The IoUringEntry containing the IoUring instance
    * @param channel
    *   The IoUringSocket to read from
    * @param buffer
    *   The ByteBuffer to read into
    * @return
    *   IO[Unit]
    */
  def queueRead(consumer: Consumer[ByteBuffer], channel: IoUringSocket, buffer: java.nio.ByteBuffer): IO[Unit] =
    // rwqMutex.lock.use(_ =>
    for {
      _ <- IO(channel.onRead(consumer))
      queueReadIO <- IO(IO(ring.queueRead(channel, buffer)))
      _ <- q.offer(queueReadIO.void)
    } yield ()
  // )

  /** Synchronized wrapper for IoUring's queueWrite method.
    *
    * @param entry
    *   The IoUringEntry containing the IoUring instance
    * @param channel
    *   The IoUringSocket to write to
    * @param buffer
    *   The ByteBuffer to write from
    * @return
    *   IO[Unit]
    */
  def queueWrite(consumer: Consumer[ByteBuffer], channel: IoUringSocket, buffer: java.nio.ByteBuffer): IO[Unit] =
    // rwqMutex.lock.use(_ =>
    for {
      _ <- IO(channel.onWrite(consumer))
      queueWriteIO <- IO(IO(ring.queueWrite(channel, buffer)))
      _ <- q.offer(queueWriteIO.void)
    } yield ()
  // )

}

/** IoUringTbl manages a collection of IoUring instances. It provides a method to get the least used IoUring instance
  * based on reference counters.
  *
  * @param entries
  *   List of IoUringEntry instances
  */
class IoUringTbl(entries: List[IoUringEntry]) {

  /** Get the IoUringEntry with the lowest reference counter value. This helps distribute the load across multiple
    * IoUring instances.
    *
    * @return
    *   IO containing the IoUringEntry with the lowest counter value
    */
  def get: IO[IoUringEntry] = {
    for {
      T <- entries.map(rec => { (rec.cntr.get.map((rec, _))) }).sequence

      c <- IO(T.minBy(_._2))
      (entry, count) = c

      b <- entry.cntr.tryUpdate(_ + 1).flatMap {
        case true  => IO(entry)
        case false => get
      }
    } yield (b)
  }

  /** Release an IoUringEntry by decrementing its counter.
    *
    * @param entry
    *   The IoUringEntry to release
    * @return
    *   IO[Unit]
    */
  def release(entry: IoUringEntry): IO[Unit] = {
    entry.cntr.get.flatMap { currentCount =>
      if (currentCount <= 0) {
        IO.raiseError(new IllegalStateException("Cannot release IoUringEntry: counter is already at 0"))
      } else {
        entry.cntr.tryUpdate(_ - 1).flatMap {
          case true  => IO.unit
          case false => release(entry)
        }
      }
    }
  }

  /** Get the total number of entries in the table.
    *
    * @return
    *   The number of IoUringEntry instances
    */
  def size: Int = entries.size

  def closeIoURings = {
    IO(entries.foreach(_.ring.close()))
  }
}

object IoUringTbl {

  @volatile
  var shutdown = false

  var server: QuartzH2Server = null

  def getCqesProcessor(entry: IoUringEntry): IO[Unit] = {
    val processCqes = IO
      .blocking(entry.ring.getCqes(9000))
      .handleErrorWith { case _: Throwable =>
        Logger[IO].error("IoUring: ring shutdown") >> IO(IoUringTbl.shutdown = true) >> server.shutdown
      }

    // Continue until shutdown becomes true
    processCqes
      .iterateUntil(_ => IoUringTbl.shutdown)
      .void
  }

  /** Processes I/O events for a specific IoUringEntry.
    *
    * This method creates a continuous processing loop that waits for signals from the queue when read/write operations
    * are enqueued, executes the IoUring event loop to process completion events, and continues the loop to handle
    * subsequent events.
    *
    * The processor terminates gracefully when the queue is shut down or an error occurs. Each IoUringEntry should have
    * its own processor running to handle its events.
    *
    * @param entry
    *   The IoUringEntry whose events will be processed
    * @return
    *   An IO that runs continuously until the queue is shut down
    */

  def submitProcessor(entry: IoUringEntry): IO[Unit] = {
    val processSubmit = for {
      queueOpIO <- entry.q.take
      _ <- queueOpIO *> IO(entry.ring.submit())
    } yield ()

    processSubmit
      .handleErrorWith { case e: Throwable =>
        Logger[IO].error(s"${e.toString()} - IoUring: submission queue shutdown") >>
          // IO(e.printStackTrace()) >>
          IO(IoUringTbl.shutdown = true) >> server.shutdown
      }
      .iterateUntil(_ => IoUringTbl.shutdown)
  }

  /** Create a new IoUringTbl with the specified number of IoUring instances.
    *
    * @param count
    *   Number of IoUring instances to create
    * @param ringSize
    *   Size of each IoUring instance
    * @return
    *   IO containing a new IoUringTbl
    */
  def apply(server: QuartzH2Server, count: Int, ringSize: Int = 1024): IO[IoUringTbl] = {
    IO(this.server = server) >>
      (0 until count)
        .map(_ =>
          for {
            mutex <- Mutex[IO]
            q <- Queue.bounded[IO, IO[Unit]](1024)
            counter <- Ref.of[IO, Int](0)
            ring <- IO(new IoUring(ringSize))
          } yield IoUringEntry(q, /*mutex,*/ counter, ring)
        )
        .toList
        .sequence
        .flatMap { entries =>
          val tbl = new IoUringTbl(entries)
          // Start a processor for each IoUringEntry
          entries
            .traverse { entry =>
              submitProcessor(entry).start >> getCqesProcessor(entry).start
            }
            .as(tbl)
        }
  }
}
