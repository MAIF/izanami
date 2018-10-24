package domains

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import cats.kernel.{Monoid, Semigroup}
import env.DbDomainConfig
import play.api.Logger
import play.api.libs.json.Reads.pattern
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc.BodyParser
import store.Result.{AppErrors, ErrorMessage, Result}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import scala.util.matching.Regex

sealed trait AuthorizedPatternTag

object AuthorizedPattern {

  import shapeless.tag
  import shapeless.tag.@@

  type AuthorizedPattern = String @@ AuthorizedPatternTag

  def apply(str: String): AuthorizedPattern = tag[AuthorizedPatternTag][String](str)

  implicit val reads: Reads[AuthorizedPattern]   = __.read[String](pattern("^[\\w@\\.0-9\\-,:\\*]+$".r)).map(apply _)
  implicit val writes: Writes[AuthorizedPattern] = Writes[AuthorizedPattern](JsString.apply)
}

object Import {
  import akka.stream.scaladsl.{Flow, Framing}
  val newLineSplit =
    Framing.delimiter(ByteString("\n"), 10000, allowTruncation = true)
  val toJson = Flow[ByteString] via newLineSplit map (_.utf8String) filterNot (_.isEmpty) map (l => (l, Json.parse(l)))

  def ndJson(implicit ec: ExecutionContext): BodyParser[Source[(String, JsValue), _]] =
    BodyParser { req =>
      Accumulator.source[ByteString].map(s => Right(s.via(toJson)))
    }

  def importFile(
      db: DbDomainConfig,
      process: Flow[(String, JsValue), ImportResult, NotUsed]
  )(implicit ec: ExecutionContext, materializer: Materializer) =
    db.`import`.foreach { p =>
      Logger.info(s"Importing file $p for namespace ${db.conf.namespace}")
      FileIO.fromPath(p).via(toJson).via(process).runWith(Sink.head).onComplete {
        case Success(res) if res.isError =>
          Logger.info(s"Import end with error for file $p and namespace ${db.conf.namespace}: \n ${res.errors}")
        case Success(_) => Logger.info(s"Import end with success for file $p and namespace ${db.conf.namespace}")
        case Failure(e) => Logger.error(s"Import end with error for file $p and namespace ${db.conf.namespace}", e)
      }
    }
}

case class ImportResult(success: Int = 0, errors: AppErrors = AppErrors()) {
  def isError = !errors.isEmpty
}

object ImportResult {
  import cats.syntax.semigroup._

  implicit val format = Json.format[ImportResult]

  implicit val monoid = new Monoid[ImportResult] {
    override def empty = ImportResult()
    override def combine(x: ImportResult, y: ImportResult) = (x, y) match {
      case (ImportResult(s1, e1), ImportResult(s2, e2)) =>
        ImportResult(s1 + s2, e1 |+| e2)
    }
  }

  def error(e: ErrorMessage) = ImportResult(errors = AppErrors(errors = Seq(e)))

  def fromResult[T](r: Result[T]): ImportResult = r match {
    case Right(_)  => ImportResult(success = 1)
    case Left(err) => ImportResult(errors = err)
  }

}

trait AuthInfo {
  def authorizedPattern: String
}

trait Jsoneable {
  def toJson: JsValue
}

object Domain {
  sealed trait Domain
  case object Experiment extends Domain
  case object ApiKey     extends Domain
  case object Config     extends Domain
  case object Feature    extends Domain
  case object User       extends Domain
  case object Script     extends Domain
  case object Webhook    extends Domain

  val reads: Reads[Domain] = Reads[Domain] {
    case JsString(s) if s == "Experiment" => JsSuccess(Experiment)
    case JsString(s) if s == "ApiKey"     => JsSuccess(ApiKey)
    case JsString(s) if s == "Config"     => JsSuccess(Config)
    case JsString(s) if s == "Feature"    => JsSuccess(Feature)
    case JsString(s) if s == "User"       => JsSuccess(User)
    case JsString(s) if s == "Webhook"    => JsSuccess(Webhook)
    case _                                => JsError("domain.invalid")
  }

  val writes: Writes[Domain] = Writes[Domain] {
    case Experiment => JsString("Experiment")
    case ApiKey     => JsString("ApiKey")
    case Config     => JsString("Config")
    case Feature    => JsString("Feature")
    case User       => JsString("User")
    case Script     => JsString("Script")
    case Webhook    => JsString("Webhook")
  }

  implicit val format: Format[Domain] = Format(reads, writes)
}

case class Key(key: String) {

  def matchPattern(str: String): Boolean = {
    val regex = Key.buildRegexPattern(str)
    key.matches(regex)
  }

  def matchPatterns(str: String*): Boolean =
    str.forall(s => matchPattern(s))

  def /(path: String): Key = key match {
    case "" => Key(s"$path")
    case _  => Key(s"$key:$path")
  }

  def /(path: Key): Key = key match {
    case "" => path
    case _  => Key(s"$key:${path.key}")
  }

  val segments: Seq[String] = key.split(":")

  val jsPath: JsPath = segments.foldLeft[JsPath](JsPath) { (p, s) =>
    p \ s
  }

  def dropHead: Key = Key(segments.tail)

  def drop(prefix: String): Key =
    if (key.startsWith(prefix)) {
      val newKey = key.drop(prefix.length)
      if (newKey.startsWith(":")) {
        Key(newKey.drop(1))
      } else {
        Key(newKey)
      }
    } else {
      this
    }
}

case class Node(key: String, childs: List[Node] = Nil, value: Option[JsValue] = None)

object Node {
  implicit val format = Json.format[Node]

  def valuesToNodes[T](vals: List[(Key, T)])(implicit writes: Writes[T]): List[Node] =
    deepMerge(vals.map {
      case (k, v) => keyValueToNodes(k.segments.toList, writes.writes(v))
    })

  def valuesToNodes(vals: List[(Key, JsValue)]): List[Node] =
    deepMerge(vals.map {
      case (k, v) => keyValueToNodes(k.segments.toList, v)
    })

  def keyValueToNodes(segments: List[String], jsValue: JsValue): Node =
    segments match {
      case Nil          => throw new IllegalArgumentException("Should not append")
      case head :: Nil  => Node(head, Nil, Some(jsValue))
      case head :: tail => Node(head, List(keyValueToNodes(tail, jsValue)), None)
    }

  def deepMerge(values: List[Node]): List[Node] =
    values
      .groupBy(_.key)
      .map {
        case (k, nodes) =>
          val value = nodes.flatMap(_.value).headOption
          Node(k, deepMerge(nodes.flatMap(_.childs)), value)
      }
      .toList
}

trait IsAllowed[T] {
  def isAllowed(value: T)(auth: Option[AuthInfo]): Boolean
}

object IsAllowed {
  def apply[T](implicit IsAllowed: IsAllowed[T]): IsAllowed[T] = IsAllowed
}

object Key {

  import play.api.libs.json.Reads._
  import play.api.libs.json._

  val Empty: Key = Key("")

  def apply(path: Seq[String]): Key = new Key(path.mkString(":"))

  private[domains] def buildRegexPattern(pattern: String): String =
    if (pattern.isEmpty) "$^"
    else {
      val newPattern = pattern.replaceAll("\\*", ".*")
      s"^$newPattern$$"
    }

  private[domains] def buildRegex(pattern: String): Regex =
    buildRegexPattern(pattern).r

  def isAllowed(key: Key)(auth: Option[AuthInfo]): Boolean = {
    val pattern = buildRegex(auth.map(_.authorizedPattern).getOrElse(""))
    key.key match {
      case pattern(_*) => true
      case _           => false
    }
  }

  def isAllowed(patternToCheck: String)(auth: Option[AuthInfo]): Boolean = {
    val pattern = buildRegex(auth.map(_.authorizedPattern).getOrElse(""))
    patternToCheck match {
      case pattern(_*) => true
      case _           => false
    }
  }

  val reads: Reads[Key] =
    __.read[String](pattern("(([\\w@\\.0-9\\-]+)(:?))+".r)).map(Key.apply)
  val writes: Writes[Key] = Writes[Key] { k =>
    JsString(k.key)
  }

  implicit val format: Format[Key] = Format(reads, writes)

}
