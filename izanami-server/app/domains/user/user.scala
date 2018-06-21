package domains.user

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import com.auth0.jwt.interfaces.DecodedJWT
import domains.events.EventStore
import domains.user.UserStore.UserKey
import domains.{AuthInfo, AuthorizedPattern, ImportResult, Key}
import libs.crypto.Sha
import play.api.libs.json.JsObject
import store.Result.ErrorMessage
import store.SourceUtils.SourceKV
import store._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class User(id: String,
                name: String,
                email: String,
                password: Option[String] = None,
                admin: Boolean,
                authorizedPattern: AuthorizedPattern.AuthorizedPattern)
    extends AuthInfo {

  override def isAllowed(auth: Option[AuthInfo]) =
    Key.isAllowed(authorizedPattern)(auth)
}

object UserNoPassword {
  import play.api.libs.json.{Format, Json}

  private val writes = {
    import domains.AuthorizedPattern._
    Json.writes[User].transform { o: JsObject =>
      o - "password"
    }
  }

  implicit val format: Format[User] = Format(User.reads, writes)
}

object User {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import play.api.libs.json.Reads.{email, pattern}

  private[user] val reads: Reads[User] = {
    import domains.AuthorizedPattern._
    (
      (__ \ 'id).read[String] and
      (__ \ 'name).read[String](pattern("^[\\p{L} .'-]+$".r)) and
      (__ \ 'email).read[String](email) and
      (__ \ 'password).readNullable[String] and
      (__ \ 'admin).read[Boolean] and
      (__ \ 'authorizedPattern).read[AuthorizedPattern](AuthorizedPattern.reads)
    )(User.apply _)
  }

  private val writes = {
    import domains.AuthorizedPattern._
    Json.writes[User]
  }

  implicit val format = Format[User](reads, writes)

  def isAllowed(pattern: String)(auth: Option[AuthInfo]) =
    Key.isAllowed(pattern)(auth)

  def fromJwtToken(jwt: DecodedJWT): Option[User] = {
    import scala.collection.JavaConverters._
    val claims = jwt.getClaims.asScala
    for {
      name   <- claims.get("name").map(_.asString())
      userId <- claims.get("user_id").map(_.asString())
      email  <- claims.get("email").map(_.asString())
      patterns = claims
        .get("izanami_authorized_patterns")
        .map(_.asString())
        .getOrElse("")
      isAdmin = claims
        .get("izanami_admin")
        .map(_.asString)
        .flatMap(str => Try(str.toBoolean).toOption)
        .getOrElse(false)
    } yield
      User(id = userId, name = name, email = email, admin = isAdmin, authorizedPattern = AuthorizedPattern(patterns))
  }

  def fromOtoroshiJwtToken(jwt: DecodedJWT): Option[User] = {
    import scala.collection.JavaConverters._
    val claims = jwt.getClaims.asScala
    for {
      name   <- claims.get("name").map(_.asString())
      userId = claims.get("user_id").map(_.asString()).orElse(claims.get("sub").map(_.asString())).getOrElse("NA")
      email  = claims.get("email").map(_.asString()).getOrElse("NA")
      patterns = claims
        .get("izanami_authorized_patterns")
        .map(_.asString())
        .getOrElse("")
      isAdmin = claims
        .get("izanami_admin")
        .map(_.asString)
        .flatMap(str => Try(str.toBoolean).toOption)
        .getOrElse(false)
    } yield
      User(id = userId, name = name, email = email, admin = isAdmin, authorizedPattern = AuthorizedPattern(patterns))
  }

  def importData(
      userStore: UserStore
  )(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import store.Result.AppErrors._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, UserNoPassword.format.reads(json)) }
      .mapAsync(4) {
        case (_, JsSuccess(obj, _)) =>
          userStore.create(Key(obj.id), obj) map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          FastFuture.successful(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }
}

trait UserStore extends DataStore[UserKey, User]
object UserStore {
  type UserKey = Key

  def apply(jsonStore: JsonDataStore, eventStore: EventStore, system: ActorSystem): UserStore =
    new UserStoreImpl(jsonStore, eventStore, system)

}

class UserStoreImpl(jsonStore: JsonDataStore, eventStore: EventStore, system: ActorSystem) extends UserStore {
  import User._
  import domains.events.Events._
  import store.Result._
  import system.dispatcher

  implicit val s  = system
  implicit val es = eventStore

  override def create(id: UserKey, data: User): Future[Result[User]] = {
    val mayBePass = data.password
    mayBePass match {
      case Some(p) =>
        val user = data.copy(password = Some(Sha.hexSha512(p)))
        jsonStore.create(id, format.writes(user)).to[User].andPublishEvent { r =>
          UserCreated(id, r)
        }
      case _ => FastFuture.successful(Result.error("password.missing"))
    }
  }

  override def update(oldId: UserKey, id: UserKey, data: User): Future[Result[User]] =
    this.getById(oldId).one.flatMap {
      case Some(oldValue) =>
        val user = data.copy(password = data.password.map(p => Sha.hexSha512(p)))
        jsonStore.update(oldId, id, format.writes(user)).to[User].andPublishEvent { r =>
          UserUpdated(id, oldValue, r)
        }
      case None =>
        Future.successful(Result.errors(ErrorMessage("error.data.missing", oldId.key)))
    }

  override def delete(id: UserKey): Future[Result[User]] =
    jsonStore.delete(id).to[User].andPublishEvent { r =>
      UserDeleted(id, r)
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: UserKey): FindResult[User] =
    JsonFindResult[User](jsonStore.getById(id))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): Future[PagingResult[User]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, User), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[User]

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)

}
