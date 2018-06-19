package domains.feature

import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal
import java.time.{LocalDateTime, ZoneId}

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import domains.events.EventStore
import domains.feature.FeatureStore._
import store.Result.{ErrorMessage, Result}
import domains.script.{GlobalScript, Script}
import domains.{AuthInfo, ImportResult, Key}
import env.Env
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import shapeless.syntax
import store._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing.MurmurHash3
import FeatureType._
import domains.config.Config
import store.SourceUtils.SourceKV
sealed trait Strategy

sealed trait Feature {
  def id: FeatureKey
  def enabled: Boolean

  def isAllowed = Key.isAllowed(id) _

  def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Result[Boolean]] =
    FastFuture.successful(Result.ok(true))

  def toJson(active: Boolean) =
    Json.toJson(this).as[JsObject] ++ Json.obj("active" -> active)

}

/* *************************************************
 ***************** DEFAULT FEATURE *****************
 ***************************************************/

case class DefaultFeature(id: FeatureKey, enabled: Boolean, parameters: JsValue = JsNull) extends Feature

object DefaultFeature {

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
}

/* *************************************************
 ************** GLOBAL SCRIPT FEATURE **************
 ***************************************************/

case class GlobalScriptFeature(id: FeatureKey, enabled: Boolean, ref: String) extends Feature {
  override def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Result[Boolean]] = {
    import domains.script.GlobalScript._
    env.globalScriptStore.getById(Key(ref)).one.flatMap {
      case Some(gs: GlobalScript) =>
        gs.source.run(context, env).map(Result.ok)
      case None => FastFuture.successful(Result.error("script.not.found"))
    }
  }
}

object GlobalScriptFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  val writes: Writes[GlobalScriptFeature] = (
    Feature.commonWrite and
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

}

/* *************************************************
 ****************** SCRIPT FEATURE *****************
 ***************************************************/

case class ScriptFeature(id: FeatureKey, enabled: Boolean, script: Script) extends Feature {
  override def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Result[Boolean]] =
    script.run(context, env).map(Result.ok)

}

object ScriptFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  val writes: Writes[ScriptFeature] = (
    Feature.commonWrite and
    (__ \ "parameters" \ "script").write[Script]
  )(unlift(ScriptFeature.unapply))
    .transform { o: JsObject =>
      o ++ Json.obj("activationStrategy" -> SCRIPT)
    }

  private val reads: Reads[ScriptFeature] = (
    (__ \ "id").read[Key] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "parameters" \ "script").read[Script]
  )(ScriptFeature.apply _)

  implicit val format: Format[ScriptFeature] = Format(reads, writes)

}

/* *************************************************
 *************** DATE RANGE FEATURE ****************
 ***************************************************/

case class DateRangeFeature(id: FeatureKey, enabled: Boolean, from: LocalDateTime, to: LocalDateTime) extends Feature {
  override def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Result[Boolean]] = {
    val now: LocalDateTime = LocalDateTime.now(ZoneId.of("Europe/Paris"))
    val active             = (now.isAfter(from) || now.isEqual(from)) && (now.isBefore(to) || now.isEqual(to))
    FastFuture.successful(Result.ok(active))
  }
}

object DateRangeFeature {
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
    Feature.commonWrite and
    (__ \ "parameters" \ "from").write[LocalDateTime](dateWrite) and
    (__ \ "parameters" \ "to").write[LocalDateTime](dateWrite)
  )(unlift(DateRangeFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> DATE_RANGE)
  }

  implicit val format: Format[DateRangeFeature] = Format(reads, writes)
}

/* *************************************************
 ************** RELEASE DATE FEATURE ***************
 ***************************************************/

case class ReleaseDateFeature(id: FeatureKey, enabled: Boolean, date: LocalDateTime) extends Feature {
  override def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Result[Boolean]] = {
    val now: LocalDateTime = LocalDateTime.now(ZoneId.of("Europe/Paris"))
    FastFuture.successful(Result.ok(now.isAfter(date)))
  }
}

object ReleaseDateFeature {
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
    Feature.commonWrite and
    (__ \ "parameters" \ "releaseDate")
      .write[LocalDateTime](temporalWrites[LocalDateTime, String](pattern))
  )(unlift(ReleaseDateFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> RELEASE_DATE)
  }

  implicit val format: Format[ReleaseDateFeature] = Format(reads, writes)
}

/* *************************************************
 *************** PERCENTAGE FEATURE ****************
 ***************************************************/

case class PercentageFeature(id: FeatureKey, enabled: Boolean, percentage: Int) extends Feature {

  override def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Result[Boolean]] =
    ((context \ "id").asOpt[String], enabled) match {
      case (Some(theId), true) =>
        val hash: Int = Math.abs(MurmurHash3.stringHash(theId))
        if (hash % 100 < percentage) {
          FastFuture.successful(Result.ok(true))
        } else {
          FastFuture.successful(Result.ok(false))
        }
      case (None, true) =>
        FastFuture.successful(Result.error("context.id.missing"))
      case _ =>
        FastFuture.successful(Result.ok(false))
    }
}

object PercentageFeature {
  import play.api.libs.json._
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._

  val reads: Reads[PercentageFeature] = (
    (__ \ "id").read[Key] and
    (__ \ "enabled").read[Boolean] and
    (__ \ "parameters" \ "percentage").read[Int](min(0) keepAnd max(100))
  )(PercentageFeature.apply _)

  val writes: Writes[PercentageFeature] = (
    Feature.commonWrite and
    (__ \ "parameters" \ "percentage").write[Int]
  )(unlift(PercentageFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> PERCENTAGE)
  }

  implicit val format: Format[PercentageFeature] = Format(reads, writes)
}

/* *************************************************
 ******************** FEATURE **********************
 ***************************************************/

object FeatureType {
  val NO_STRATEGY   = "NO_STRATEGY"
  val RELEASE_DATE  = "RELEASE_DATE"
  val DATE_RANGE    = "DATE_RANGE"
  val SCRIPT        = "SCRIPT"
  val GLOBAL_SCRIPT = "GLOBAL_SCRIPT"
  val PERCENTAGE    = "PERCENTAGE"
}

object Feature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import FeatureType._

  def isAllowed(key: FeatureKey)(auth: Option[AuthInfo]) =
    Key.isAllowed(key)(auth)

  def importData(
      featureStore: FeatureStore
  )(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import store.Result.AppErrors._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, json.validate[Feature]) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          featureStore.create(obj.id, obj) map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

  def graphWrites(active: Boolean): Writes[Feature] = Writes[Feature] { feature =>
    val path = feature.id.segments.foldLeft[JsPath](JsPath) { (path, seq) =>
      path \ seq
    }
    val writer = (path \ "active").write[Boolean]
    writer.writes(active)
  }

  def withActive(context: JsObject,
                 env: Env)(implicit ec: ExecutionContext): Flow[Feature, (Boolean, Feature), NotUsed] =
    Flow[Feature]
      .mapAsyncUnordered(2) { feature =>
        feature
          .isActive(context, env)
          .map {
            case Right(active) => (active && feature.enabled, feature)
            case Left(_)       => (false, feature)
          }
          .recover {
            case _ => (false, feature)
          }
      }

  def flat(context: JsObject, env: Env)(implicit ec: ExecutionContext): Flow[Feature, JsValue, NotUsed] =
    Flow[Feature]
      .via(withActive(context, env))
      .map {
        case (active, f) => f.toJson(active)
      }
      .fold(Seq.empty[JsValue]) { _ :+ _ }
      .map(JsArray(_))

  def toGraph(context: JsObject, env: Env)(implicit ec: ExecutionContext): Flow[Feature, JsObject, NotUsed] =
    Flow[Feature]
      .via(withActive(context, env))
      .map {
        case (active, f) => Feature.graphWrites(active).writes(f)
      }
      .fold(Json.obj()) { (acc, js) =>
        acc.deepMerge(js.as[JsObject])
      }

  def tree(flatRepr: Boolean)(context: JsObject,
                              env: Env)(implicit ec: ExecutionContext): Flow[Feature, JsValue, NotUsed] =
    if (flatRepr) flat(context, env)
    else toGraph(context, env)

  private[feature] val commonWrite =
  (__ \ "id").write[FeatureKey] and
  (__ \ "enabled").write[Boolean]

  val reads: Reads[Feature] = Reads[Feature] {
    case o if (o \ "activationStrategy").asOpt[String].contains(NO_STRATEGY) =>
      import DefaultFeature._
      o.validate[DefaultFeature]
    case o if (o \ "activationStrategy").asOpt[String].contains(RELEASE_DATE) =>
      import ReleaseDateFeature._
      o.validate[ReleaseDateFeature]
    case o if (o \ "activationStrategy").asOpt[String].contains(DATE_RANGE) =>
      import DateRangeFeature._
      o.validate[DateRangeFeature]
    case o if (o \ "activationStrategy").asOpt[String].contains(SCRIPT) =>
      import ScriptFeature._
      o.validate[ScriptFeature]
    case o if (o \ "activationStrategy").asOpt[String].contains(GLOBAL_SCRIPT) =>
      import GlobalScriptFeature._
      o.validate[GlobalScriptFeature]
    case o if (o \ "activationStrategy").asOpt[String].contains(PERCENTAGE) =>
      import PercentageFeature._
      o.validate[PercentageFeature]
    case _ =>
      JsError("invalid json")
  }

  val writes: Writes[Feature] = Writes[Feature] {
    case s: DefaultFeature      => Json.toJson(s)(DefaultFeature.format)
    case s: ReleaseDateFeature  => Json.toJson(s)(ReleaseDateFeature.format)
    case s: DateRangeFeature    => Json.toJson(s)(DateRangeFeature.format)
    case s: ScriptFeature       => Json.toJson(s)(ScriptFeature.format)
    case s: GlobalScriptFeature => Json.toJson(s)(GlobalScriptFeature.format)
    case s: PercentageFeature   => Json.toJson(s)(PercentageFeature.format)
  }

  implicit val format: Format[Feature] = Format(reads, writes)

}

trait FeatureStore extends DataStore[FeatureKey, Feature]

object FeatureStore {

  type FeatureKey = Key

  sealed trait FeatureMessages

  def apply(jsonStore: JsonDataStore, eventStore: EventStore, system: ActorSystem): FeatureStore =
    new FeatureStoreImpl(jsonStore, eventStore, system)

}

class FeatureStoreImpl(jsonStore: JsonDataStore, eventStore: EventStore, system: ActorSystem) extends FeatureStore {

  import Feature._
  import domains.events.Events._
  import store.Result._
  import system.dispatcher

  implicit val s  = system
  implicit val es = eventStore

  override def create(id: FeatureKey, data: Feature): Future[Result[Feature]] =
    jsonStore.create(id, format.writes(data)).to[Feature].andPublishEvent { r =>
      FeatureCreated(id, r)
    }

  override def update(oldId: FeatureKey, id: FeatureKey, data: Feature): Future[Result[Feature]] =
    this.getById(oldId).one.flatMap {
      case Some(oldValue) =>
        jsonStore
          .update(oldId, id, format.writes(data))
          .to[Feature]
          .andPublishEvent { r =>
            FeatureUpdated(id, oldValue, r)
          }
      case None =>
        Future.successful(Result.errors(ErrorMessage("error.data.missing", oldId.key)))
    }

  override def delete(id: FeatureKey): Future[Result[Feature]] =
    jsonStore.delete(id).to[Feature].andPublishEvent { r =>
      FeatureDeleted(id, r)
    }
  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: FeatureKey): FindResult[Feature] =
    JsonFindResult[Feature](jsonStore.getById(id))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[Feature]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, Feature), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[Feature]

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)

}
