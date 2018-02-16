package domains.user

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.interfaces.DecodedJWT
import domains.events.EventStore
import domains.user.UserStore.UserKey
import domains.{AuthInfo, Key}
import libs.crypto.Sha
import play.api.libs.json.{Format, JsObject, Json}
import store._

import scala.concurrent.Future
import scala.util.Try

case class User(id: String,
                name: String,
                email: String,
                password: Option[String] = None,
                admin: Boolean,
                authorizedPattern: String)
    extends AuthInfo {

  override def isAllowed(auth: Option[AuthInfo]) =
    Key.isAllowed(authorizedPattern)(auth)
}

object UserNoPassword {
  private val reads = Json.reads[User]
  private val writes = Json.writes[User].transform { o: JsObject =>
    o - "password"
  }
  implicit val format: Format[User] = Format(reads, writes)
}

object User {
  implicit val format = Json.format[User]

  def isAllowed(pattern: String)(auth: Option[AuthInfo]) =
    Key.isAllowed(pattern)(auth)

  def fromJwtToken(jwt: DecodedJWT): Option[User] = {
    import scala.collection.JavaConverters._
    val claims = jwt.getClaims.asScala
    for {
      name   <- claims.get("name").map(_.asString())
      userId <- claims.get("user_id").map(_.asString())
      email  = claims.get("email").map(_.asString()).getOrElse("")
      patterns = claims
        .get("izanami_authorized_patterns")
        .map(_.asString())
        .getOrElse("")
      isAdmin = claims
        .get("izanami_admin")
        .map(_.asString)
        .flatMap(str => Try(str.toBoolean).toOption)
        .getOrElse(false)
    } yield User(id = userId, name = name, email = email, admin = isAdmin, authorizedPattern = patterns)
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
    jsonStore.update(oldId, id, format.writes(data)).to[User].andPublishEvent { r =>
      UserUpdated(id, data, r)
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

  override def getByIdLike(patterns: Seq[String]): FindResult[User] =
    JsonFindResult[User](jsonStore.getByIdLike(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)

}
