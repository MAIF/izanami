package domains.feature
import java.time.{LocalDateTime, ZoneId}

import cats.Applicative
import cats.effect.{Async, Effect}
import domains.{AuthInfo, IsAllowed, Key}
import domains.script._
import env.Env
import shapeless.syntax
import store.Result
import store.Result.{AppErrors, IzanamiErrors, Result}
import FeatureType._
import domains.feature.Feature.FeatureKey
import domains.script.Script.ScriptCache
import zio.{IO, Task, ZIO}

import scala.util.hashing.MurmurHash3

object DefaultFeatureInstances {

  import play.api.libs.json._
  import playjson.all._
  import syntax.singleton._
  val reads: Reads[DefaultFeature] = jsonRead[DefaultFeature].withRules(
    'parameters ->> orElse[JsValue](JsNull)
  )
  val writes: Writes[DefaultFeature] = Json
    .writes[DefaultFeature]
    .transform { o: JsObject =>
      (o \ "parameters").as[JsValue] match {
        case JsNull =>
          o - "parameters" ++ Json.obj("activationStrategy" -> NO_STRATEGY)
        case _ =>
          o ++ Json.obj("activationStrategy" -> NO_STRATEGY)
      }
    }

  implicit val format: Format[DefaultFeature] = Format(reads, writes)

  def isActive: IsActive[DefaultFeature] = new IsActive[DefaultFeature] {
    override def isActive(feature: DefaultFeature,
                          context: JsObject): ZIO[ScriptCacheModule with GlobalScriptContext, IzanamiErrors, Boolean] =
      ZIO.succeed(feature.enabled)
  }
}

object GlobalScriptFeatureInstances {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  val writes: Writes[GlobalScriptFeature] = (
    FeatureInstances.commonWrite and
    (__ \ "parameters" \ "ref").write[String]
  )(unlift(GlobalScriptFeature.unapply))
    .transform { o: JsObject =>
      o ++ Json.obj("activationStrategy" -> GLOBAL_SCRIPT)
    }

  private val reads: Reads[GlobalScriptFeature] = (
    (__ \ "id").read[Key] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "parameters" \ "ref").read[String]
  )(GlobalScriptFeature.apply _)

  implicit val format: Format[GlobalScriptFeature] = Format(reads, writes)

  def isActive: IsActive[GlobalScriptFeature] =
    new IsActive[GlobalScriptFeature] {

      import domains.script.syntax._
      import domains.script.ScriptInstances._

      override def isActive(
          feature: GlobalScriptFeature,
          context: JsObject
      ): ZIO[IsActiveContext, IzanamiErrors, Boolean] =
        for {
          mayBeScript <- GlobalScriptService.getById(Key(feature.ref)).refineToOrDie[IzanamiErrors]
          script      <- ZIO.fromOption(mayBeScript).mapError(_ => AppErrors.error("script.not.found"))
          exec <- script.source
                   .run(context)
                   .map {
                     case ScriptExecutionSuccess(result, _) => result
                     case _                                 => false
                   }
                   .refineToOrDie[IzanamiErrors]
        } yield exec
    }
}

object ScriptFeatureInstances {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  val writes: Writes[ScriptFeature] = (
    FeatureInstances.commonWrite and
    (__ \ "parameters").write[Script](ScriptInstances.writes)
  )(unlift(ScriptFeature.unapply))
    .transform { o: JsObject =>
      o ++ Json.obj("activationStrategy" -> SCRIPT)
    }

  private val reads: Reads[ScriptFeature] = (
    (__ \ "id").read[Key] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "parameters").read[Script](ScriptInstances.reads)
  )(ScriptFeature.apply _)

  implicit val format: Format[ScriptFeature] = Format(reads, writes)

  def isActive: IsActive[ScriptFeature] = new IsActive[ScriptFeature] {
    import cats.implicits._
    import domains.script.syntax._
    import domains.script.ScriptInstances._
    override def isActive(feature: ScriptFeature, context: JsObject): ZIO[IsActiveContext, IzanamiErrors, Boolean] =
      feature.script
        .run(context)
        .map {
          case ScriptExecutionSuccess(result, _) => result
          case _                                 => false
        }
        .refineToOrDie[IzanamiErrors]
  }
}

object DateRangeFeatureInstances {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads.localDateTimeReads
  import play.api.libs.json.Writes.temporalWrites
  import play.api.libs.json._

  private[feature] val pattern = "yyyy-MM-dd HH:mm:ss"

  val reads: Reads[DateRangeFeature] = (
    (__ \ "id").read[Key] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "parameters" \ "from")
      .read[LocalDateTime](localDateTimeReads(pattern)) and
    (__ \ "parameters" \ "to")
      .read[LocalDateTime](localDateTimeReads(pattern))
  )(DateRangeFeature.apply _)

  private val dateWrite: Writes[LocalDateTime] = temporalWrites[LocalDateTime, String](pattern)

  val writes: Writes[DateRangeFeature] = (
    FeatureInstances.commonWrite and
    (__ \ "parameters" \ "from").write[LocalDateTime](dateWrite) and
    (__ \ "parameters" \ "to").write[LocalDateTime](dateWrite)
  )(unlift(DateRangeFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> DATE_RANGE)
  }

  implicit val format: Format[DateRangeFeature] = Format(reads, writes)

  def isActive: IsActive[DateRangeFeature] = new IsActive[DateRangeFeature] {
    import cats.implicits._
    override def isActive(feature: DateRangeFeature,
                          context: JsObject): ZIO[IsActiveContext, IzanamiErrors, Boolean] = {
      val now: LocalDateTime = LocalDateTime.now(ZoneId.of("Europe/Paris"))
      val active = (now.isAfter(feature.from) || now.isEqual(feature.from)) && (now.isBefore(feature.to) || now.isEqual(
        feature.to
      ))
      ZIO.succeed(active)
    }
  }

}

object ReleaseDateFeatureInstances {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads.localDateTimeReads
  import play.api.libs.json.Writes.temporalWrites
  import play.api.libs.json._

  private[feature] val pattern  = "dd/MM/yyyy HH:mm:ss"
  private[feature] val pattern2 = "dd/MM/yyyy HH:mm"
  private[feature] val pattern3 = "yyyy-MM-dd HH:mm:ss"

  val reads: Reads[ReleaseDateFeature] = (
    (__ \ "id").read[Key] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "parameters" \ "releaseDate")
      .read[LocalDateTime](
        localDateTimeReads(pattern).orElse(localDateTimeReads(pattern2)).orElse(localDateTimeReads(pattern3))
      )
  )(ReleaseDateFeature.apply _)

  val writes: Writes[ReleaseDateFeature] = (
    FeatureInstances.commonWrite and
    (__ \ "parameters" \ "releaseDate")
      .write[LocalDateTime](temporalWrites[LocalDateTime, String](pattern))
  )(unlift(ReleaseDateFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> RELEASE_DATE)
  }

  implicit val format: Format[ReleaseDateFeature] = Format(reads, writes)

  def isActive: IsActive[ReleaseDateFeature] = new IsActive[ReleaseDateFeature] {
    import cats.implicits._
    override def isActive(
        feature: ReleaseDateFeature,
        context: JsObject
    ): ZIO[ScriptCacheModule with GlobalScriptContext, IzanamiErrors, Boolean] = {
      val now: LocalDateTime = LocalDateTime.now(ZoneId.of("Europe/Paris"))
      ZIO.succeed(now.isAfter(feature.date))
    }
  }
}

object PercentageFeatureInstances {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads._
  import play.api.libs.json._

  val reads: Reads[PercentageFeature] = (
    (__ \ "id").read[Key] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "parameters" \ "percentage").read[Int](min(0) keepAnd max(100))
  )(PercentageFeature.apply _)

  val writes: Writes[PercentageFeature] = (
    FeatureInstances.commonWrite and
    (__ \ "parameters" \ "percentage").write[Int]
  )(unlift(PercentageFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> PERCENTAGE)
  }

  implicit val format: Format[PercentageFeature] = Format(reads, writes)

  def isActive: IsActive[PercentageFeature] = new IsActive[PercentageFeature] {
    import cats.implicits._
    override def isActive(feature: PercentageFeature,
                          context: JsObject): ZIO[ScriptCacheModule with GlobalScriptContext, IzanamiErrors, Boolean] =
      ((context \ "id").asOpt[String], feature.enabled) match {
        case (Some(theId), true) =>
          val hash: Int = Math.abs(MurmurHash3.stringHash(theId))
          if (hash % 100 < feature.percentage) {
            ZIO.succeed(true)
          } else {
            ZIO.succeed(false)
          }
        case (None, true) =>
          ZIO.fail(AppErrors.error("context.id.missing"))
        case _ =>
          ZIO.succeed(false)
      }
  }
}

object FeatureInstances {
  import FeatureType._
  import cats.implicits._
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val isAllowed: IsAllowed[Feature] = new IsAllowed[Feature] {
    override def isAllowed(value: Feature)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value.id)(auth)
  }

  def isActive(feature: Feature, context: JsObject): ZIO[IsActiveContext, IzanamiErrors, Boolean] =
    ZIO.accessM { cxt =>
      import zio.interop.catz._

      (feature match {
        case f: DefaultFeature      => DefaultFeatureInstances.isActive.isActive(f, context)
        case f: GlobalScriptFeature => GlobalScriptFeatureInstances.isActive.isActive(f, context)
        case f: ScriptFeature       => ScriptFeatureInstances.isActive.isActive(f, context)
        case f: DateRangeFeature    => DateRangeFeatureInstances.isActive.isActive(f, context)
        case f: ReleaseDateFeature  => ReleaseDateFeatureInstances.isActive.isActive(f, context)
        case f: PercentageFeature   => PercentageFeatureInstances.isActive.isActive(f, context)
      })
    }

  implicit val isActive: IsActive[Feature] =
    new IsActive[Feature] {
      override def isActive(feature: Feature, context: JsObject): ZIO[IsActiveContext, IzanamiErrors, Boolean] =
        feature match {
          case f: DefaultFeature      => DefaultFeatureInstances.isActive.isActive(f, context)
          case f: GlobalScriptFeature => GlobalScriptFeatureInstances.isActive.isActive(f, context)
          case f: ScriptFeature       => ScriptFeatureInstances.isActive.isActive(f, context)
          case f: DateRangeFeature    => DateRangeFeatureInstances.isActive.isActive(f, context)
          case f: ReleaseDateFeature  => ReleaseDateFeatureInstances.isActive.isActive(f, context)
          case f: PercentageFeature   => PercentageFeatureInstances.isActive.isActive(f, context)
        }
    }

  def graphWrites(active: Boolean): Writes[Feature] = Writes[Feature] { feature =>
    val path = feature.id.segments.foldLeft[JsPath](JsPath) { (path, seq) =>
      path \ seq
    }
    val writer = (path \ "active").write[Boolean]
    writer.writes(active)
  }

  private[feature] val commonWrite =
  (__ \ "id").write[FeatureKey] and
  (__ \ "enabled").write[Boolean]

  val reads: Reads[Feature] = Reads[Feature] {
    case o if (o \ "activationStrategy").asOpt[String].contains(NO_STRATEGY) =>
      DefaultFeatureInstances.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(RELEASE_DATE) =>
      ReleaseDateFeatureInstances.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(DATE_RANGE) =>
      DateRangeFeatureInstances.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(SCRIPT) =>
      ScriptFeatureInstances.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(GLOBAL_SCRIPT) =>
      GlobalScriptFeatureInstances.format.reads(o)
    case o if (o \ "activationStrategy").asOpt[String].contains(PERCENTAGE) =>
      PercentageFeatureInstances.format.reads(o)
    case _ =>
      JsError("invalid json")
  }

  val writes: Writes[Feature] = Writes[Feature] {
    case s: DefaultFeature      => Json.toJson(s)(DefaultFeatureInstances.format)
    case s: ReleaseDateFeature  => Json.toJson(s)(ReleaseDateFeatureInstances.format)
    case s: DateRangeFeature    => Json.toJson(s)(DateRangeFeatureInstances.format)
    case s: ScriptFeature       => Json.toJson(s)(ScriptFeatureInstances.format)
    case s: GlobalScriptFeature => Json.toJson(s)(GlobalScriptFeatureInstances.format)
    case s: PercentageFeature   => Json.toJson(s)(PercentageFeatureInstances.format)
  }

  implicit val format: Format[Feature] = Format(reads, writes)

}
