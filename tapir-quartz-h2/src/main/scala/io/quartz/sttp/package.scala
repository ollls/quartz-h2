package io.quartz
import cats.effect.IO

package object sttp {

   type QuartzH2ResponseBody = (fs2.Stream[IO, Byte], Option[String])
  
}
