package fr.maif.izanami.utils

import fr.maif.izanami.utils.SseSubscriber.extractMessageData

import java.net.http.HttpResponse.BodySubscriber
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util
import java.util.concurrent.{CompletableFuture, CompletionStage, Flow}
import java.util.regex.Pattern

class SseSubscriber(consumer: String => Unit) extends BodySubscriber[Void] {
  var deferredText: String = null
  var subscription: Flow.Subscription = null
  var future: CompletableFuture[Void] = new CompletableFuture[Void]()

  override def getBody: CompletionStage[Void] = this.future

  override def onSubscribe(subscription: Flow.Subscription): Unit = {
    this.subscription = subscription
    try {
      this.deferredText = ""
      this.subscription.request(1)
    } catch {
      case e: Throwable => {
        this.future.completeExceptionally(e)
        this.subscription.cancel()
      }
    }
  }

  override def onNext(buffers: util.List[ByteBuffer]): Unit = {
    try {
      var deferredText = this.deferredText
      buffers.forEach(buffer => {
        val s = deferredText + StandardCharsets.UTF_8.decode( buffer )
        val token = s.split("\n\n", -1)
        token.dropRight(1).foreach(msg => {
          val lines = msg.split("\n")
          val data = extractMessageData(lines.toIndexedSeq)
          this.consumer(data)
          // TODO: Handle lines that start with "event:", "id:", "retry:"
        })
        deferredText = token.last
      })
      this.deferredText = deferredText
      this.subscription.request(1)
    } catch {
      case e: Throwable => {
        this.future.completeExceptionally(e)
        this.subscription.cancel()
      }
    }
  }

  override def onError(throwable: Throwable): Unit = this.future.completeExceptionally(throwable)

  override def onComplete(): Unit = try {
    this.future.complete(null)
  } catch {
    case e: Throwable => {
      this.future.completeExceptionally(e)
    }
  }
}

object SseSubscriber {
  val DATA_LINE_PATTERN: Pattern = Pattern.compile("^data: ?(.*)$")

  def extractMessageData(lines: Seq[String]): String = {
    val s = StringBuilder
    lines.foreach(line => {
      val matcher = DATA_LINE_PATTERN.matcher(line)
      if(matcher.matches()) {
        String.valueOf(s) + matcher.group(1)
      }
    })
    s.toString
  }
}