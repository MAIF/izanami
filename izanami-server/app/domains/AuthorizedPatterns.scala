package domains

import cats.implicits._
import cats.data.NonEmptySet
import cats.kernel.Order
import domains.errors.{IzanamiErrors, Unauthorized}
import libs.logs.LoggerModule
import play.api.libs.json.Reads.pattern
import zio.ZIO

import scala.collection.immutable
import scala.util.matching.Regex

trait PatternRight

object PatternRight {
  import play.api.libs.json._
  case object Read   extends PatternRight with Product with Serializable
  case object Create extends PatternRight with Product with Serializable
  case object Update extends PatternRight with Product with Serializable
  case object Delete extends PatternRight with Product with Serializable

  val C: PatternRight = Read
  val R: PatternRight = Create
  val U: PatternRight = Update
  val D: PatternRight = Delete

  private val order = List(Create, Read, Update, Delete)

  private def index(p: PatternRight): Int = order.indexOf(p)

  def stringValue(r: PatternRight): String = r match {
    case Create => "C"
    case Read   => "R"
    case Update => "U"
    case Delete => "D"
  }

  val reads: Reads[PatternRight] = Reads[PatternRight] {
    case JsString(s) =>
      fromString(s) match {
        case Some(p) => JsSuccess(p)
        case None    => JsError(JsonValidationError("error.unknown.right", s))
      }
    case other => JsError(JsonValidationError("error.unexpected.type", other))
  }

  val writes: Writes[PatternRight] = Writes[PatternRight] { r =>
    JsString(stringValue(r))
  }

  implicit val format: Format[PatternRight] = Format(reads, writes)

  def fromString(s: String): Option[PatternRight] = s match {
    case "R" => Some(Read)
    case "C" => Some(Create)
    case "U" => Some(Update)
    case "D" => Some(Delete)
    case _   => None
  }

  implicit val eq: cats.Eq[PatternRight] = cats.Eq.fromUniversalEquals

  implicit val ord: Order[PatternRight] = Order.from { (p1, p2) =>
    index(p1) - index(p2)
  }
}

abstract case class PatternRights private[PatternRights] (rights: NonEmptySet[PatternRight]) {

  def ++(p: PatternRights): PatternRights =
    PatternRights.fromNES(rights ++ p.rights)

  def isAllowed(patternRight: PatternRights): Boolean =
    patternRight.rights.map(r => rights.contains_(r)).reduceLeft(_ && _)
}

object PatternRights {
  import play.api.libs.json._
  import play.api.libs.json.Reads._
  import PatternRight._

  val C: PatternRights    = PatternRights(Read, Create)
  val R: PatternRights    = PatternRights(Read)
  val U: PatternRights    = PatternRights(Read, Update)
  val D: PatternRights    = PatternRights(Read, Delete)
  val CRUD: PatternRights = PatternRights(Create, Read, Update, Delete)

  def fromNES(rights: NonEmptySet[PatternRight]): PatternRights = new PatternRights(rights.add(Read)) {}

  def apply(other: PatternRight*): PatternRights =
    /*_*/ fromNES(NonEmptySet.of(Read, other: _*) /*_*/ )

  def fromList(l: List[PatternRight]): PatternRights = {
    val value: immutable.SortedSet[PatternRight] = immutable.SortedSet.from(l)
    /*_*/
    NonEmptySet.fromSet(value).map(PatternRights.fromNES _).getOrElse(PatternRights.CRUD)
    /*_*/
  }

  val reads: Reads[PatternRights] = __.read[List[PatternRight]].map(l => fromList(l))

  val writes: Writes[PatternRights] = Writes[PatternRights] { rights =>
    Json.toJson(rights.rights.toList)
  }
  implicit val format: Format[PatternRights] = Format(reads, writes)

  def stringValue(r: PatternRights): String = r.rights.toList.map(PatternRight.stringValue).mkString("")

  def fromString(str: String): PatternRights = {
    val option: Option[List[PatternRight]] = str.split("").toList.traverse {
      PatternRight.fromString
    }
    option.map(fromList).getOrElse(PatternRights.CRUD)
  }

}

case class AuthorizedPattern(pattern: String, rights: PatternRights = PatternRights.CRUD) {

  private val p = AuthorizedPattern.buildRegex(pattern)

  def patternMatch(str: String): Boolean =
    str match {
      case p(_*) => true
      case _     => false
    }

  def stringify: String = s"$pattern$$$$$$${PatternRights.stringValue(rights)}"

  def isAllowed(strPattern: String, patternRight: PatternRights): Boolean =
    rights.isAllowed(patternRight) && patternMatch(strPattern)
}

object AuthorizedPattern {

  import PatternRights._
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  def of(pattern: String, other: PatternRight*): AuthorizedPattern =
    AuthorizedPattern(pattern, PatternRights(other: _*))

  private def buildRegexPattern(pattern: String): String =
    if (pattern.isEmpty) "$^"
    else {
      val newPattern = pattern.replaceAll("\\*", ".*")
      s"^$newPattern$$"
    }
  private def buildRegex(pattern: String): Regex =
    buildRegexPattern(pattern).r

  val regex = "^([\\w@\\.0-9\\-:\\*]+)(\\$\\$\\$)?([CRUD]{0,4})?$".r

  def fromString(str: String): Option[AuthorizedPattern] =
    str match {
      case regex(p, _, rights) =>
        Some(AuthorizedPattern(p, PatternRights.fromString(rights)))
      case _ => None
    }

  def stringValue(p: AuthorizedPattern): String = s"${p.pattern}$$$$$$${PatternRights.stringValue(p.rights)}"

  private val objectReads: Reads[AuthorizedPattern] = (
    (__ \ "pattern").read[String](pattern("^[\\w@\\.0-9\\-:\\*]+$".r)) and
    (__ \ "rights").read[PatternRights](PatternRights.format)
  )(AuthorizedPattern.apply _)

  private val stringRead = Reads[AuthorizedPattern] {
    case JsString(str) =>
      AuthorizedPattern.fromString(str) match {
        case Some(p) => JsSuccess(p)
        case None    => JsError(JsonValidationError("error.unknown.pattern", str))
      }
    case other => JsError(JsonValidationError("error.unexpected.type", other))
  }

  val reads: Reads[AuthorizedPattern] = objectReads.orElse(stringRead)

  val writes: Writes[AuthorizedPattern] = (
    (__ \ "pattern").write[String] and
    (__ \ "rights").write[PatternRights](PatternRights.format)
  )(unlift(AuthorizedPattern.unapply _))

  implicit val format: Format[AuthorizedPattern] = Format(reads, writes)

}

case class AuthorizedPatterns(patterns: AuthorizedPattern*)

object AuthorizedPatterns {
  import play.api.libs.json._

  def of(t: (String, PatternRights)*): AuthorizedPatterns = AuthorizedPatterns(
    t.map { case (p, r) => AuthorizedPattern(p, r) }: _*
  )

  def parse(str: String): Option[AuthorizedPatterns] = {
    val mayBePatterns: Option[List[AuthorizedPattern]] =
      str.split(",").toList.map { AuthorizedPattern.fromString }.sequence
    mayBePatterns.map(AuthorizedPatterns.apply)
  }

  def fromString(str: String): AuthorizedPatterns = parse(str).getOrElse(AuthorizedPatterns())

  def stringValue(p: AuthorizedPatterns): String = p.patterns.map(AuthorizedPattern.stringValue).mkString(",")

  def stringify(patterns: AuthorizedPatterns): String =
    patterns.patterns.map(_.stringify).mkString(",")

  val All: AuthorizedPatterns = AuthorizedPatterns(AuthorizedPattern("*", PatternRights.CRUD))

  val objectReads: Reads[AuthorizedPatterns] = __.read[List[AuthorizedPattern]].map(AuthorizedPatterns.apply)

  val stringReads: Reads[AuthorizedPatterns] = Reads[AuthorizedPatterns] {
    case JsString(str) =>
      parse(str) match {
        case None    => JsError(JsonValidationError("error.unknown.format", str))
        case Some(p) => JsSuccess(p)
      }
    case _ => JsError("error.unexpected.type")
  }

  val reads: Reads[AuthorizedPatterns] = objectReads.orElse(stringReads)

  val writes: Writes[AuthorizedPatterns] = Writes[AuthorizedPatterns] { p =>
    Json.toJson(p.patterns.toList)
  }

  implicit val format: Format[AuthorizedPatterns] = Format(reads, writes)

  def isAllowed(key: String, patternRight: PatternRights, patterns: AuthorizedPatterns): Boolean =
    patterns.patterns.exists(_.isAllowed(key, patternRight))

  def isAllowed(key: Key, patternRight: PatternRights, patterns: AuthorizedPatterns): Boolean =
    isAllowed(key.key, patternRight, patterns)

  def isAllowed(key: String,
                patternRight: PatternRights): ZIO[LoggerModule with AuthInfoModule[_], IzanamiErrors, Unit] =
    ZIO.accessM[LoggerModule with AuthInfoModule[_]] { ctx =>
      ZIO.when(!ctx.authInfo.map(_.authorizedPatterns).exists(p => isAllowed(key, patternRight, p))) {
        ctx.logger.debug(s"${ctx.authInfo} is allowed to access $key with $patternRight") *> ZIO.fail(
          IzanamiErrors(Unauthorized(Some(Key(key))))
        )
      }
    }

  def isAllowed(key: Key, patternRight: PatternRights): ZIO[LoggerModule with AuthInfoModule[_], IzanamiErrors, Unit] =
    ZIO.accessM[LoggerModule with AuthInfoModule[_]] { ctx =>
      ZIO.when(!ctx.authInfo.map(_.authorizedPatterns).exists(p => isAllowed(key, patternRight, p))) {
        ctx.logger.debug(s"${ctx.authInfo} is allowed to access $key with $patternRight") *> ZIO.fail(
          IzanamiErrors(Unauthorized(Some(key)))
        )
      }
    }

  def isAllowed(patterns: (Key, PatternRights)*): ZIO[LoggerModule with AuthInfoModule[_], IzanamiErrors, Unit] = {
    import cats.implicits._
    ZIO.accessM[LoggerModule with AuthInfoModule[_]] { ctx =>
      ZIO.effectSuspendTotal {
        val value: Either[IzanamiErrors, List[Unit]] = patterns.toList.parTraverse {
          case (k, r) =>
            Either.cond(
              ctx.authInfo.map(_.authorizedPatterns).exists(p => isAllowed(k, r, p)),
              (),
              IzanamiErrors(Unauthorized(Some(k)))
            )
        }
        ZIO.fromEither(value).unit
      }
    }
  }

}
