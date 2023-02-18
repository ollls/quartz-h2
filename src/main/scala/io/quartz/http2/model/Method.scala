package io.quartz.http2.model

sealed case class Method(val name: String) {
  override def toString: String = name
}
/**
  * An enumeration of HTTP methods.
  * This object provides constants for the standard HTTP methods such as GET, POST, PUT, DELETE, etc.
  */
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
