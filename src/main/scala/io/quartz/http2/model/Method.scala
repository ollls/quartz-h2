package io.quartz.http2.model

sealed case class Method(val name: String) {
  override def toString: String = name
}

object Method {
  def apply(name: String) = new Method(name)
  val GET     = Method("GET")
  val HEAD    = Method("HEAD")
  val POST    = Method("POST")
  val PUT     = Method("PUT")
  val DELETE  = Method("DELETE")
  val CONNECT = Method("CONNECT")
  val OPTIONS = Method("OPTIONS")
  val TRACE   = Method("TRACE")
  val PATCH   = Method("PATCH")

}
