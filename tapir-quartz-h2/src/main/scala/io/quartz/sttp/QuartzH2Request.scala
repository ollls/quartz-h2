package io.quartz.sttp

import sttp.tapir.model.{ConnectionInfo, ServerRequest}
import sttp.tapir.{AttributeKey, AttributeMap}
import io.quartz.http2.model.Request
import io.quartz.http2.model.Method

import sttp.model.Uri
import sttp.model.Method
import sttp.model.Header
import sttp.model.QueryParams

case class QuartzH2Request(r: Request, attributes: AttributeMap = AttributeMap.Empty) extends ServerRequest {

  override def method: sttp.model.Method =  sttp.model.Method( r.method.name )
  override def headers: Seq[Header] = r.headers.tbl.map( h => Header( h._1, h._2.mkString( ",") )).toSeq
  override def queryParameters: QueryParams = uri.params
  override def protocol: String = "h2" //"HTTP/1.1"
  override def connectionInfo: ConnectionInfo = ConnectionInfo(None, None, None)
  override def underlying: Any = r
  override def pathSegments: List[String] = (uri.pathSegments.segments.map(_.v) match {
    case other :+ "" => other
    case s           => s
  }).toList
  override def uri: Uri = Uri.unsafeParse(r.uri.toString())
  override def attribute[T](k: AttributeKey[T]): Option[T] = attributes.get(k)
  override def attribute[T](k: AttributeKey[T], v: T):  ServerRequest = copy(attributes = attributes.put(k, v))

  override def withUnderlying(underlying: Any): ServerRequest = {
    require(underlying.isInstanceOf[io.quartz.http2.model.Request])
    QuartzH2Request(underlying.asInstanceOf[io.quartz.http2.model.Request], attributes) 
  }
  
}
