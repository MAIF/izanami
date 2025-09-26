package fr.maif.izanami.utils.syntax

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.utils.FutureEither
import org.apache.commons.codec.binary.Hex
import play.api.Logger
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.concurrent.{ExecutionContext, Future}

object implicits {
  implicit class BetterSyntax[A](private val obj: A) extends AnyVal {
    @inline
    def vfuture: Future[A]                                          = FastFuture.successful(obj)
    def seq: Seq[A]                                                 = Seq(obj)
    def set: Set[A]                                                 = Set(obj)
    def list: List[A]                                               = List(obj)
    def some: Option[A]                                             = Some(obj)
    def option: Option[A]                                           = Some(obj)
    def left[B]: Either[A, B]                                       = Left(obj)
    def right[B]: Either[B, A]                                      = Right(obj)
    def future: Future[A]                                           = FastFuture.successful(obj)
    def fEither: FutureEither[A]                                    = FutureEither.success(obj)
    def asFuture: Future[A]                                         = FastFuture.successful(obj)
    def toFuture: Future[A]                                         = FastFuture.successful(obj)
    def somef: Future[Option[A]]                                    = FastFuture.successful(Some(obj))
    def leftf[B]: Future[Either[A, B]]                              = FastFuture.successful(Left(obj))
    def rightf[B]: Future[Either[B, A]]                             = FastFuture.successful(Right(obj))
    def debug(f: A => Any): A = {
      f(obj)
      obj
    }
    def debugPrintln: A = {
      println(obj)
      obj
    }
    def debugLogger(logger: Logger): A = {
      logger.debug(s"$obj")
      obj
    }
    def applyOn[B](f: A => B): B                                    = f(obj)
    def applyOnIf(predicate: => Boolean)(f: A => A): A              = if (predicate) f(obj) else obj
    def applyOnWithOpt[B](opt: => Option[B])(f: (A, B) => A): A     = if (opt.isDefined) f(obj, opt.get) else obj
    def applyOnWithPredicate(predicate: A => Boolean)(f: A => A): A = if (predicate(obj)) f(obj) else obj

    def seffectOn(f: A => Unit): A = {
      f(obj)
      obj
    }
    def seffectOnIf(predicate: => Boolean)(f: A => Unit): A = {
      if (predicate) {
        f(obj)
        obj
      } else obj
    }
    def seffectOnWithPredicate(predicate: A => Boolean)(f: A => Unit): A = {
      if (predicate(obj)) {
        f(obj)
        obj
      } else obj
    }
  }

  implicit class BetterListEither[E,V](private val obj: Iterable[Either[E, V]]) extends AnyVal {
    def toEitherList: Either[List[E], List[V]] = {
      obj.foldLeft(Right(List()):Either[List[E], List[V]]){
        case (Left(errors), Left(error)) => Left(errors.appended(error))
        case (l@Left(_), _) => l
        case (Right(values), Right(value)) => Right(values.appended(value))
        case (_, Left(error)) => Left(List(error))
      }
    }
  }

  implicit class BetterFutureEither[V](private val obj: Future[Either[IzanamiError, V]]) extends AnyVal {
    def toFEither: FutureEither[V] = {
        FutureEither(obj)
    }
  }

  implicit class BetterFuture[V](private val obj: Future[V]) extends AnyVal {
    def mapToFEither(implicit ec: ExecutionContext) : FutureEither[V] = {
      FutureEither(obj.map(v => Right(v)))
    }
  }

  implicit class BetterEither[V](private val obj: Either[IzanamiError, V]) extends AnyVal {
    def toFEither: FutureEither[V] = {
      FutureEither(Future.successful(obj))
    }
  }



  implicit class BetterBoolean[E,V](private val b: Boolean) extends AnyVal {
    def toJava: java.lang.Boolean = java.lang.Boolean.valueOf(b)
  }


  implicit class BetterJsValue(private val obj: JsValue) extends AnyVal {
    def stringify: String                    = Json.stringify(obj)
    def prettify: String                     = Json.prettyPrint(obj)
    def select(name: String): JsLookupResult = obj \ name
    def select(index: Int): JsLookupResult   = obj \ index
    def at(path: String): JsLookupResult = {
      val parts = path.split("\\.").toSeq
      parts.foldLeft(obj) {
        case (source: JsObject, part) => (source \ part).as[JsValue]
        case (source: JsArray, part)  => (source \ part.toInt).as[JsValue]
        case (value, part)            => JsNull
      } match {
        case JsNull => JsUndefined(s"path '${path}' does not exists")
        case value  => JsDefined(value)
      }
    }
    def atPointer(path: String): JsLookupResult = {
      val parts = path.split("/").toSeq.filterNot(_.trim.isEmpty)
      parts.foldLeft(obj) {
        case (source: JsObject, part) => (source \ part).as[JsValue]
        case (source: JsArray, part)  => (source \ part.toInt).as[JsValue]
        case (value, part)            => JsNull
      } match {
        case JsNull => JsUndefined(s"path '${path}' does not exists")
        case value  => JsDefined(value)
      }
    }
    def vertxJsValue: Object                 = io.vertx.core.json.Json.decodeValue(Json.stringify(obj))
  }

  implicit class BetterByteString(private val obj: ByteString) extends AnyVal {
    def chunks(size: Int): Source[ByteString, NotUsed] = Source(obj.grouped(size).toList)

    def sha256: String = Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(obj.toArray))

    def sha512: String = Hex.encodeHexString(MessageDigest.getInstance("SHA-512").digest(obj.toArray))
  }

  implicit class BetterString(private val obj: String) extends AnyVal {
    def byteString: ByteString = ByteString(obj)

    def bytes: Array[Byte] = obj.getBytes(StandardCharsets.UTF_8)

    def json: JsValue = JsString(obj)

    def parseJson: JsValue = Json.parse(obj)

    def camelToSnake: String = {
      obj.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase
      // obj.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase
    }

    def sha512: String = Hex.encodeHexString(
      MessageDigest.getInstance("SHA-512").digest(obj.getBytes(StandardCharsets.UTF_8))
    )

    def sha256: String                             = Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(obj.getBytes(StandardCharsets.UTF_8)))
  }
}
