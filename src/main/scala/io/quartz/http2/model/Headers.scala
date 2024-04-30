package io.quartz.http2.model

import scala.collection.immutable.HashMap
import scala.collection.immutable.HashSet
import scala.collection.mutable.StringBuilder

import java.lang.Character

object Headers {

  val http2Hdrs = HashSet(":method", ":path", ":authority", ":scheme", ":status")
  val http2Hdrs_request = HashSet(":method", ":path", ":authority", ":scheme")

  def apply(args: (String, String)*) = {
    args.foldLeft(new Headers())((hdr, pair) => hdr + pair)
  }

  def apply(pair: (String, String)) = {
    new Headers(pair)
  }

  def apply() = new Headers()

}

/** An immutable collection of HTTP headers, represented as a mapping from header names to lists of values.
  * @param tbl
  *   a HashMap containing header names as keys and lists of values as associated values
  */
class Headers(val tbl: HashMap[String, List[String]]) {

  def this(pair: (String, String)) = this(HashMap[String, List[String]]((pair._1, List(pair._2))))

  def this() = this(HashMap[String, List[String]]())

  def cookie(cookie: Cookie) = updated("set-cookie" -> cookie.toString())

  def contentType(type0: ContentType) = updated("content-type" -> type0.toString())

  def +(pair: (String, String)) = updated(pair)

  def ++(hdrs: Headers): Headers =
    new Headers(hdrs.tbl.foldLeft(tbl)((tbl0, pair) => {
      val key = pair._1
      val mval_set = tbl0.get(key).getOrElse(List.empty[String])
      tbl0.updated(key, mval_set ++ pair._2)
    }))

  def updated(pair: (String, String)) = {
    val key = pair._1
    val mval_set = tbl.get(key).getOrElse(List.empty[String])

    val tbl_u = tbl.updated(key, mval_set :+ pair._2)

    new Headers(tbl_u)
  }

  def drop(key: String): Headers = new Headers(tbl.removed(key))

  def get(key: String): Option[String] = tbl.get(key).map(set => set.head)

  def getMval(key: String): List[String] = tbl.get(key).getOrElse(List.empty[String])

  def foreach(op: (String, String) => Unit): Unit =
    tbl.foreach(kv => kv._2.foreach(a_val => op(kv._1, a_val)))

  def iterator = tbl.iterator

  def printHeaders: String = {
    val ZT = new StringBuilder()

    val lines = tbl.foldLeft(ZT) {
      ((lines, kv) => kv._2.foldLeft(lines)((lz, val2) => lz.append(kv._1 + ": " + val2.toString + "\n")))
    }
    lines.toString()
  }

  def printHeaders(separator: String): String = {
    val ZT = new StringBuilder()

    val lines = tbl.foldLeft(ZT) {
      ((lines, kv) => kv._2.foldLeft(lines)((lz, val2) => lz.append(kv._1 + ": " + val2.toString + separator)))
    }
    lines.toString()
  }

  def ensureLowerCase: Boolean =
    tbl.forall { case (key, _) => key.forall(c => !Character.isLetter(c) || Character.isLowerCase(c)) }

  def isPseudoHeadersPresent: Boolean =
    tbl.forall { case (key, _) => !key.startsWith(":") }

  def validatePseudoHeaders: Boolean = {
    val test1 = tbl.forall { case (key, _) =>
      if (!key.startsWith(":")) true else Headers.http2Hdrs_request.contains(key)
    }

    val test2 = tbl.get(":path") match {
      case Some(val0) => if (val0.size > 1 || val0.head.isBlank()) false else true
      case None       => false
    }

    val test3 = tbl.get(":method") match {
      case Some(val0) => if (val0.size > 1 || val0.head.isBlank()) false else true
      case None       => false
    }

    val test4 = tbl.get(":scheme") match {
      case Some(val0) => if (val0.size > 1 || val0.head.isBlank()) false else true
      case None       => false
    }

    test1 && test2 && test3 && test4
  }

}
