package io.quartz.sttp

import cats.effect.Sync
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sttp.tapir.server.interceptor.log.DefaultServerLog
import org.typelevel.log4cats.LoggerName

object QuartzH2DefaultServerLog {

  def apply[F[_]: Sync]: DefaultServerLog[F] = {
    val log = Slf4jLogger.getLogger[F](using cats.effect.kernel.Sync[F], new LoggerName("tapir"))
    DefaultServerLog(
      doLogWhenReceived = msg => debugLog(log)(msg, None),
      doLogWhenHandled = debugLog(log),
      doLogAllDecodeFailures = debugLog(log),
      doLogExceptions = (msg: String, ex: Throwable) => log.error(ex)(msg),
      noLog = Sync[F].pure(())
    )
  }

  private def debugLog[F[_]](log: Logger[F])(msg: String, exOpt: Option[Throwable]): F[Unit] =
    exOpt match {
      case None     => log.debug(msg)
      case Some(ex) => log.debug(ex)(msg)
    }
}
