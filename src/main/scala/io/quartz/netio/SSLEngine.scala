package io.quartz.netio

import cats.effect.IO

import javax.net.ssl.{SSLEngine => JSSLEngine}

import javax.net.ssl.SSLEngineResult

import java.nio.ByteBuffer
import java.lang.Runnable

final class SSLEngine(val engine: JSSLEngine) {

  def wrap(src: ByteBuffer, dst: ByteBuffer): IO[SSLEngineResult] = IO(engine.wrap(src, dst))

  def unwrap(src: ByteBuffer, dst: ByteBuffer): IO[SSLEngineResult] = {
    IO(engine.unwrap(src, dst))
  }

  def closeInbound() = IO(engine.closeInbound())

  def closeOutbound() = IO(engine.closeOutbound())

  def isOutboundDone() = IO(engine.isOutboundDone)

  def isInboundDone() = IO(engine.isInboundDone)

  def setUseClientMode(use: Boolean) = IO(engine.setUseClientMode(use))

  def setNeedClientAuth(use: Boolean) = IO(engine.setNeedClientAuth(use))

  def getDelegatedTask() = IO.blocking {
    var task: Runnable = engine.getDelegatedTask();

    while (task != null) {
      task.run()
      task = engine.getDelegatedTask();
    }  
  }

  def getHandshakeStatus(): IO[SSLEngineResult.HandshakeStatus] =
    IO(engine.getHandshakeStatus())

}
