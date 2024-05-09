package io.quartz
import cats.effect.IO
import fs2.Pipe
import io.quartz.websocket.WebSocketFrame

package object sttp {

  type QuartzH2ResponseBody = Either[
    IO[Pipe[IO, WebSocketFrame, WebSocketFrame]],
    (fs2.Stream[IO, Byte], Option[String])
  ]

}
