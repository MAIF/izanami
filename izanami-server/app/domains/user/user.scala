package domains.user

import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Source}
import cats.data.EitherT
import cats.effect.Effect
import com.auth0.jwt.interfaces.DecodedJWT
import domains.events.EventStore
import domains.user.User.UserKey
import domains._
import libs.crypto.Sha
import libs.functional.EitherTSyntax
import libs.logs.IzanamiLogger
import play.api.libs.json._
import store.Result.Result
import store._

import scala.concurrent.ExecutionContext
import scala.util.Try

case class User(id: String,
                name: String,
                email: String,
                password: Option[String] = None,
                admin: Boolean,
                authorizedPattern: AuthorizedPattern.AuthorizedPattern)
    extends AuthInfo

object User {

  type UserKey = Key

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

}

trait UserService[F[_]] {
  def create(id: UserKey, data: User): F[Result[User]]
  def update(oldId: UserKey, id: UserKey, data: User): F[Result[User]]
  def delete(id: UserKey): F[Result[User]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]]
  def getById(id: UserKey): F[Option[User]]
  def getByIdLike(patterns: Seq[String], page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[User]]
  def getByIdLike(patterns: Seq[String]): Source[(UserKey, User), NotUsed]
  def count(patterns: Seq[String]): F[Long]
  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]
}

class UserServiceImpl[F[_]: Effect](jsonStore: JsonDataStore[F], eventStore: EventStore[F])
    extends UserService[F]
    with EitherTSyntax[F] {

  import cats.implicits._
  import libs.functional.syntax._
  import libs.streams.syntax._
  import UserInstances._
  import domains.events.Events._
  import store.Result._

  override def create(id: UserKey, data: User): F[Result[User]] = {
    // format: off
    val r: EitherT[F, AppErrors, User] = for {
      pass        <- data.password                                             |> liftOption(AppErrors.error("password.missing"))
      user        =  data.copy(password = Some(Sha.hexSha512(pass)))
      created     <- jsonStore.create(id, UserInstances.format.writes(user))   |> liftFEither
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
      user =      data.copy(password = data.password.map(p => Sha.hexSha512(p)))
      updated     <- jsonStore.update(oldId, id, UserInstances.format.writes(user))      |> liftFEither
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

  override def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import libs.streams.syntax._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, UserNoPasswordInstances.format.reads(json)) }
      .mapAsyncF(4) {
        case (_, JsSuccess(obj, _)) =>
          create(Key(obj.id), obj) map { ImportResult.fromResult }
        case (s, JsError(_)) =>
          Effect[F].pure(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    IzanamiLogger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }

}
