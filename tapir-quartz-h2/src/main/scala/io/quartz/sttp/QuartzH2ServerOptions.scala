package io.quartz.sttp

import cats.effect.Sync
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.log.DefaultServerLog
import sttp.tapir.server.interceptor.{CustomiseInterceptors, Interceptor}
import sttp.tapir.{Defaults, TapirFile}

case class QuartzH2ServerOptions[F[_]](
    createFile: ServerRequest => F[TapirFile],
    deleteFile: TapirFile => F[Unit],
    ioChunkSize: Int,
    interceptors: List[Interceptor[F]]
) {
  def prependInterceptor(i: Interceptor[F]): QuartzH2ServerOptions[F] =
    copy(interceptors = i :: interceptors)
  def appendInterceptor(i: Interceptor[F]): QuartzH2ServerOptions[F] =
    copy(interceptors = interceptors :+ i)
}

object QuartzH2ServerOptions {

  /** Allows customising the interceptors used by the server interpreter. */
  def customiseInterceptors[F[_]: Sync]: CustomiseInterceptors[F, QuartzH2ServerOptions[F]] = {
    CustomiseInterceptors(
      createOptions = (ci: CustomiseInterceptors[F, QuartzH2ServerOptions[F]]) =>
        QuartzH2ServerOptions[F](defaultCreateFile[F], defaultDeleteFile[F], 8192, ci.interceptors)
    ).serverLog(defaultServerLog)
  }

  def defaultCreateFile[F[_]](implicit sync: Sync[F]): ServerRequest => F[TapirFile] = _ => sync.blocking(Defaults.createTempFile())

  def defaultDeleteFile[F[_]](implicit sync: Sync[F]): TapirFile => F[Unit] = file => sync.blocking(Defaults.deleteFile()(file))

  def defaultServerLog[F[_]: Sync]: DefaultServerLog[F] = QuartzH2DefaultServerLog[F]

  def default[F[_]: Sync]: QuartzH2ServerOptions[F] = customiseInterceptors[F].options
}