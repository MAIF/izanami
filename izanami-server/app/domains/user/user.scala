package domains.user

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.auth0.jwt.interfaces.DecodedJWT
import domains.events.{EventStore, EventStoreContext}
import domains.user.User.UserKey
import domains._
import libs.crypto.Sha
import libs.logs.LoggerModule
import libs.ziohelper.JsResults.jsResultToError
import play.api.libs.json._
import store._
import domains.AuthInfoModule

import scala.util.Try

case class User(id: String,
                name: String,
                email: String,
                password: Option[String] = None,
                admin: Boolean,
                authorizedPattern: AuthorizedPattern.AuthorizedPattern)
    extends AuthInfo {
  override def mayBeEmail: Option[String] = Some(email)

}

object User {

  type UserKey = Key

  def updateUser(newUser: User, oldUser: User): User = {
    import cats.implicits._
    if (newUser.password === oldUser.password) {
      newUser
    } else {
      newUser.copy(password = newUser.password.map(p => Sha.hexSha512(p)))
    }
  }

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

trait UserDataStoreModule {
  def userDataStore: JsonDataStore
}

trait UserContext
    extends LoggerModule
    with DataStoreContext
    with UserDataStoreModule
    with EventStoreContext
    with AuthInfoModule[UserContext]

object UserDataStore extends JsonDataStoreHelper[UserContext] {
  override def accessStore = _.userDataStore
}

object UserService {
  import cats.implicits._
  import zio._
  import libs.streams.syntax._
  import UserInstances._
  import domains.events.Events._
  import store.Result._

  def create(id: UserKey, data: User): ZIO[UserContext, IzanamiErrors, User] =
    for {
      _        <- IO.when(Key(data.id) =!= id)(IO.fail(IdMustBeTheSame(Key(data.id), id)))
      pass     <- ZIO.fromOption(data.password).mapError(_ => AppErrors.error("password.missing"))
      user     = data.copy(password = Some(Sha.hexSha512(pass)))
      created  <- UserDataStore.create(id, UserInstances.format.writes(user))
      user     <- jsResultToError(created.validate[User])
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(UserCreated(id, user, authInfo = authInfo))
    } yield user

  def update(oldId: UserKey, id: UserKey, data: User): ZIO[UserContext, IzanamiErrors, User] =
    // format: off
    for {
      mayBeUser   <- getById(oldId).refineToOrDie[IzanamiErrors]
      oldValue    <- ZIO.fromOption(mayBeUser).mapError(_ => DataShouldExists(oldId))
      toUpdate        = User.updateUser(data, oldValue)
      updated     <- UserDataStore.update(oldId, id, UserInstances.format.writes(toUpdate))
      user        <- jsResultToError(updated.validate[User])
      authInfo    <- AuthInfo.authInfo
      _           <- EventStore.publish(UserUpdated(id, oldValue, user, authInfo = authInfo))
    } yield user
    // format: on

  def delete(id: UserKey): ZIO[UserContext, IzanamiErrors, User] =
    // format: off
    for {
      deleted   <- UserDataStore.delete(id)
      user      <- jsResultToError(deleted.validate[User])
      authInfo  <- AuthInfo.authInfo
      _         <- EventStore.publish(UserDeleted(id, user, authInfo = authInfo))
    } yield user
    // format: on

  def deleteAll(patterns: Seq[String]): ZIO[UserContext, IzanamiErrors, Unit] =
    UserDataStore.deleteAll(patterns)

  def getById(id: UserKey): RIO[UserContext, Option[User]] =
    UserDataStore.getById(id).map(_.flatMap(_.validate[User].asOpt))

  def findByQuery(query: Query, page: Int, nbElementPerPage: Int): RIO[UserContext, PagingResult[User]] =
    UserDataStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  def findByQuery(query: Query): RIO[UserContext, Source[(Key, User), NotUsed]] =
    UserDataStore.findByQuery(query).map(_.readsKV[User])

  def count(query: Query): RIO[UserContext, Long] =
    UserDataStore.count(query)

  def importData(
      strategy: ImportStrategy = ImportStrategy.Keep
  ): RIO[UserContext, Flow[(String, JsValue), ImportResult, NotUsed]] =
    ImportData
      .importDataFlow[UserContext, UserKey, User](
        strategy,
        user => Key(user.id),
        key => getById(key),
        (key, data) => create(key, data),
        (key, data) => update(key, key, data)
      )(UserInstances.format)

}
