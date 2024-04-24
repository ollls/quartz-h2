package io.quartz.sttp

import cats.effect.IO
import cats.effect.kernel.Resource.ExitCase._
import cats.Applicative
import sttp.monad.MonadError
import sttp.monad.syntax._
import sttp.tapir.server.interpreter.BodyListener

import scala.util.{Failure, Success, Try}
import fs2.{Stream, Chunk}

class QuartzH2BodyListener(implicit m: MonadError[IO], a: Applicative[IO])
    extends BodyListener[IO, QuartzH2ResponseBody] {

  override def onComplete(
      body: QuartzH2ResponseBody
  )(cb: Try[Unit] => IO[Unit]): IO[QuartzH2ResponseBody] = {

    val bo1: fs2.Stream[IO, Byte] = body.asInstanceOf[fs2.Stream[IO, Byte]]

    bo1.onFinalizeCase {
      case Succeeded | Canceled => { cb(Success(())) }
      case Errored(ex)          => cb(Failure(ex))
    }
    IO(body)
  }
}
