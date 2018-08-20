package domains.user

import akka.{Done, NotUsed}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import cats.Monad
import cats.data.EitherT
import com.auth0.jwt.interfaces.DecodedJWT
import domains.events.EventStore
import domains.user.User.UserKey
import domains.{AuthInfo, AuthorizedPattern, ImportResult, Key}
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json.{JsObject, JsPath, JsonValidationError}
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

  type UserKey = Key

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
      userStore: UserStore[Future]
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

trait UserStore[F[_]] extends DataStore[F, UserKey, User]

class UserStoreImpl[F[_]: Monad](jsonStore: JsonDataStore[F], eventStore: EventStore[F])
    extends UserStore[F]
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.functional.syntax._
  import User._
  import domains.events.Events._
  import store.Result._

  override def create(id: UserKey, data: User): F[Result[User]] = {
    // format: off
    val r: EitherT[F, AppErrors, User] = for {
      created     <- jsonStore.create(id, User.format.writes(data))   |> liftFEither
      user        <- created.validate[User]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(UserCreated(id, user))        |> liftF[AppErrors, Done]
    } yield user
    // format: on
    r.value
  }

  override def update(oldId: UserKey, id: UserKey, data: User): F[Result[User]] = {
    // format: off
    val r: EitherT[F, AppErrors, User] = for {
      oldValue    <- getById(oldId)                                             |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      updated     <- jsonStore.update(oldId, id, User.format.writes(data))      |> liftFEither
      user        <- updated.validate[User]                                     |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(UserUpdated(id, oldValue, user))        |> liftF[AppErrors, Done]
    } yield user
    // format: on
    r.value
  }

  override def delete(id: UserKey): F[Result[User]] = {
    // format: off
    val r: EitherT[F, AppErrors, User] = for {
      deleted <- jsonStore.delete(id)                       |> liftFEither
      user    <- deleted.validate[User]                     |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(UserDeleted(id, user))  |> liftF[AppErrors, Done]
    } yield user
    // format: on
    r.value
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: UserKey): F[Option[User]] =
    jsonStore.getById(id).map(_.flatMap(_.validate[User].asOpt))

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[User]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): Source[(Key, User), NotUsed] =
    jsonStore.getByIdLike(patterns).readsKV[User]

  override def count(patterns: Seq[String]): F[Long] =
    jsonStore.count(patterns)

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }

}
