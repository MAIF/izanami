package domains.feature

import java.time.{LocalDateTime, ZoneId}

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Flow
import akka.{Done, NotUsed}
import domains.events.EventStore
import domains.feature.FeatureStore._
import domains.script.{GlobalScript, Script}
import domains.{AuthInfo, Key}
import env.Env
import play.api.libs.json.{JsObject, Json}
import shapeless.syntax
import store._

import scala.concurrent.{ExecutionContext, Future}

sealed trait Strategy

sealed trait Feature {
  def id: FeatureKey
  def enabled: Boolean

  def isAllowed = Key.isAllowed(id) _

  def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Boolean] =
    FastFuture.successful(true)

  def toJson(active: Boolean) =
    Json.toJson(this).as[JsObject] ++ Json.obj("active" -> active)

}

case class DefaultFeature(id: FeatureKey, enabled: Boolean) extends Feature

object DefaultFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import playjson.all._

  val reads: Reads[DefaultFeature] = hReads[DefaultFeature]
  val writes: Writes[DefaultFeature] =
    Feature
      .commonWrite(unlift(DefaultFeature.unapply))
      .transform { o: JsObject =>
        o ++ Json.obj("activationStrategy" -> "NO_STRATEGY")
      }

  implicit val format: Format[DefaultFeature] = Format(reads, writes)
}

case class GlobalScriptFeature(id: FeatureKey, enabled: Boolean, ref: String) extends Feature {
  override def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Boolean] = {
    import domains.script.GlobalScript._
    env.globalScriptStore.getById(Key(ref)).one.flatMap {
      case Some(gs: GlobalScript) =>
        gs.source.run(context, env)
      case None => FastFuture.successful(false)
    }
  }
}

object GlobalScriptFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import playjson.all._

  val writes: Writes[GlobalScriptFeature] = (
    Feature.commonWrite and
    (__ \ "parameters" \ "ref").write[String]
  )(unlift(GlobalScriptFeature.unapply))
    .transform { o: JsObject =>
      o ++ Json.obj("activationStrategy" -> "GLOBAL_SCRIPT")
    }

  private val reads: Reads[GlobalScriptFeature] = transform(
    (__ \ 'parameters \ 'ref) to (__ \ 'ref)
  ) andThen hReads[GlobalScriptFeature]

  implicit val format: Format[GlobalScriptFeature] = Format(reads, writes)

}

case class ScriptFeature(id: FeatureKey, enabled: Boolean, script: Script) extends Feature {
  override def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Boolean] =
    script.run(context, env)

}

object ScriptFeature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import playjson.all._

  val writes: Writes[ScriptFeature] = (
    Feature.commonWrite and
    (__ \ "parameters" \ "script").write[Script]
  )(unlift(ScriptFeature.unapply))
    .transform { o: JsObject =>
      o ++ Json.obj("activationStrategy" -> "SCRIPT")
    }

  private val reads: Reads[ScriptFeature] = transform(
    (__ \ "parameters" \ "script") to (__ \ "script")
  ) andThen Json.reads[ScriptFeature]

  implicit val format: Format[ScriptFeature] = Format(reads, writes)

}

case class ReleaseDateFeature(id: FeatureKey, enabled: Boolean, date: LocalDateTime) extends Feature {
  override def isActive(context: JsObject, env: Env)(implicit ec: ExecutionContext): Future[Boolean] = {
    val now: LocalDateTime = LocalDateTime.now(ZoneId.of("Europe/Paris"))
    FastFuture.successful(now.isAfter(date))
  }
}

object ReleaseDateFeature {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads.localDateTimeReads
  import play.api.libs.json.Writes.temporalWrites
  import play.api.libs.json._
  import playjson.all._
  import syntax.singleton._

  private[feature] val pattern  = "dd/MM/yyyy HH:mm:ss"
  private[feature] val pattern2 = "dd/MM/yyyy HH:mm"

  val reads: Reads[ReleaseDateFeature] = transform(
    (__ \ "parameters" \ "releaseDate") to (__ \ "date")
  ) andThen jsonRead[ReleaseDateFeature].withRules(
    'date ->> read[LocalDateTime](localDateTimeReads(pattern).orElse(localDateTimeReads(pattern2)))
  )

  val writes: Writes[ReleaseDateFeature] = (
    Feature.commonWrite and
    (__ \ "parameters" \ "releaseDate")
      .write[LocalDateTime](temporalWrites[LocalDateTime, String](pattern))
  )(unlift(ReleaseDateFeature.unapply)).transform { o: JsObject =>
    o ++ Json.obj("activationStrategy" -> "RELEASE_DATE")
  }

  implicit val format: Format[ReleaseDateFeature] = Format(reads, writes)
}

object Feature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  def isAllowed(key: FeatureKey)(auth: Option[AuthInfo]) =
    Key.isAllowed(key)(auth)

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
          .map { active =>
            (active && feature.enabled, feature)
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
    case o if (o \ "activationStrategy").asOpt[String].contains("NO_STRATEGY") =>
      import DefaultFeature._
      o.validate[DefaultFeature]
    case o if (o \ "activationStrategy").asOpt[String].contains("RELEASE_DATE") =>
      import ReleaseDateFeature._
      o.validate[ReleaseDateFeature]
    case o if (o \ "activationStrategy").asOpt[String].contains("SCRIPT") =>
      import ScriptFeature._
      o.validate[ScriptFeature]
    case o if (o \ "activationStrategy").asOpt[String].contains("GLOBAL_SCRIPT") =>
      import GlobalScriptFeature._
      o.validate[GlobalScriptFeature]
    case other =>
      JsError("invalid json")
  }

  val writes: Writes[Feature] = Writes[Feature] {
    case s: DefaultFeature      => Json.toJson(s)(DefaultFeature.format)
    case s: ReleaseDateFeature  => Json.toJson(s)(ReleaseDateFeature.format)
    case s: ScriptFeature       => Json.toJson(s)(ScriptFeature.format)
    case s: GlobalScriptFeature => Json.toJson(s)(GlobalScriptFeature.format)
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
    jsonStore
      .update(oldId, id, format.writes(data))
      .to[Feature]
      .andPublishEvent { r =>
        FeatureUpdated(id, data, r)
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

  override def getByIdLike(patterns: Seq[String]): FindResult[Feature] =
    JsonFindResult[Feature](jsonStore.getByIdLike(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)

}
