import scala.concurrent.duration._
import cats.implicits._
import cats.effect.IO
import cats.effect.testing.minitest.IOTestSuite

object SimpleSuite extends IOTestSuite {
  override val timeout = 1.second // Default timeout is 10 seconds

  test("do the thing") {
    IO(assert(true))
  }
}
