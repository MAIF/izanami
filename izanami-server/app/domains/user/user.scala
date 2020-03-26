package domains

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import domains.events.EventStore
import domains.user.User.UserKey
import domains._
import libs.crypto.Sha
import libs.logs.ZLogger
import libs.ziohelper.JsResults.jsResultToError
import play.api.libs.json._
import store._
import store.datastore._
import domains.configuration.AuthInfoModule
import env.Oauth2Config
import errors.IzanamiErrors

import scala.util.Try

package object user {

  trait User extends AuthInfo {
    def email: String
    def admin: Boolean
    override def mayBeEmail: Option[String] = Some(email)
  }

  case class IzanamiUser(id: String,
                         name: String,
                         email: String,
                         password: Option[String],
                         admin: Boolean,
                         authorizedPatterns: AuthorizedPatterns)
      extends User

  case class OauthUser(id: String, name: String, email: String, admin: Boolean, authorizedPatterns: AuthorizedPatterns)
      extends User

  case class OtoroshiUser(id: String,
                          name: String,
                          email: String,
                          admin: Boolean,
                          authorizedPatterns: AuthorizedPatterns)
      extends User

  object User {

    type UserKey = Key

    def buildToken(user: User, issuer: String, algorithm: Algorithm) =
      JWT
        .create()
        .withIssuer(issuer)
        .withClaim("name", user.name)
        .withClaim("user_id", user.id)
        .withClaim("email", user.email)
        .withClaim("izanami_authorized_patterns", AuthorizedPatterns.stringify(user.authorizedPatterns)) // FIXME à voir si on doit mettre une liste???
        .withClaim("izanami_admin", user.admin.toString)
        .sign(algorithm)

    def fromOAuth(user: JsValue, authConfig: Oauth2Config): Either[IzanamiErrors, OauthUser] = {
      import cats.implicits._

      (Either.fromOption((user \ authConfig.idField).asOpt[String], IzanamiErrors.error("oauth.error.id.missing")),
       Either.fromOption((user \ authConfig.nameField).asOpt[String].orElse((user \ "sub").asOpt[String]),
                         IzanamiErrors.error("oauth.error.name.missing")),
       Right((user \ authConfig.emailField).asOpt[String].getOrElse("NA")),
       Right((user \ authConfig.adminField).asOpt[Boolean].getOrElse(false)),
       Right(
         (user \ authConfig.authorizedPatternField)
           .asOpt[String]
           .map(s => AuthorizedPatterns.fromString(s))
           .getOrElse(AuthorizedPatterns.fromString(authConfig.defaultPatterns))
       ))
        .parMapN(OauthUser.apply)
    }
    def updateUser(newUser: User, oldUser: User): User =
      (newUser, oldUser) match {
        case (newUser: IzanamiUser, oldUser: IzanamiUser) =>
          import cats.implicits._
          (newUser.password, oldUser.password) match {
            case (Some(password), Some(oldPassword)) if password === oldPassword =>
              newUser
            case (Some(password), _) =>
              newUser.copy(password = Some(Sha.hexSha512(password)))
            case (None, _) =>
              newUser.copy(password = oldUser.password)
          }
        case _ => newUser
      }

    def fromJwtToken(jwt: DecodedJWT): Option[User] = {
      import scala.jdk.CollectionConverters._
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
        OauthUser(id = userId,
                  name = name,
                  email = email,
                  admin = isAdmin,
                  authorizedPatterns = AuthorizedPatterns.fromString(patterns))
    }

    def fromOtoroshiJwtToken(jwt: DecodedJWT): Option[User] = {
      import scala.jdk.CollectionConverters._
      val claims = jwt.getClaims.asScala
      for {
        name   <- claims.get("name").map(_.asString()).orElse(claims.get("aud").map(_.asString()))
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
        OtoroshiUser(id = userId,
                     name = name,
                     email = email,
                     admin = isAdmin,
                     authorizedPatterns = AuthorizedPatterns.fromString(patterns))
    }

  }

  type UserDataStore = zio.Has[UserDataStore.Service]

  object UserDataStore extends JsonDataStoreHelper[UserDataStore] {

    trait Service {
      def userDataStore: JsonDataStore.Service
    }

    override def accessStore: UserDataStore => JsonDataStore.Service = _.get.userDataStore
  }

  type UserContext = UserDataStore with ZLogger with EventStore with AuthInfoModule

  object UserService {
    import cats.implicits._
    import zio._
    import libs.streams.syntax._
    import UserInstances._
    import domains.events.Events._
    import errors._
    import IzanamiErrors._

    def handlePassword(user: User): Either[IzanamiErrors, User] =
      user match {
        case u: OauthUser    => Right(u)
        case u: OtoroshiUser => Right(u)
        case u: IzanamiUser  => Right(u.password.map(p => u.copy(password = Some(Sha.hexSha512(p)))).getOrElse(u))
      }

    def createIfNotExists(id: UserKey, data: User): ZIO[UserContext, IzanamiErrors, User] =
      getById(id).flatMap {
        case Some(_) => IO.succeed(data)
        case None    => create(id, data)
      }

    def create(id: UserKey, data: User): ZIO[UserContext, IzanamiErrors, User] =
      AuthorizedPatterns.isAdminAllowed(id, PatternRights.C) *> createWithoutPermission(id, data)

    def createWithoutPermission(id: UserKey, data: User): ZIO[UserContext, IzanamiErrors, User] =
      for {
        _        <- IO.when(Key(data.id) =!= id)(IO.fail(IdMustBeTheSame(Key(data.id), id).toErrors))
        user     <- ZIO.fromEither(handlePassword(data))
        created  <- UserDataStore.create(id, UserInstances.format.writes(user))
        user     <- jsResultToError(created.validate[User])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(UserCreated(id, user, authInfo = authInfo))
      } yield user

    def update(oldId: UserKey, id: UserKey, data: User): ZIO[UserContext, IzanamiErrors, User] =
      AuthorizedPatterns.isAdminAllowed(id, PatternRights.U) *> updateWithoutPermission(oldId, id, data)

    def updateWithoutPermission(oldId: UserKey, id: UserKey, data: User): ZIO[UserContext, IzanamiErrors, User] =
      // format: off
      for {
        mayBeUser   <- getByIdWithoutPermissions(oldId).refineOrDie[IzanamiErrors](PartialFunction.empty)
        oldValue    <- ZIO.fromOption(mayBeUser).mapError(_ => DataShouldExists(oldId).toErrors)
        toUpdate    = User.updateUser(data, oldValue)
        updated     <- UserDataStore.update(oldId, id, UserInstances.format.writes(toUpdate))
        user        <- jsResultToError(updated.validate[User])
        authInfo    <- AuthInfo.authInfo
        _           <- EventStore.publish(UserUpdated(id, oldValue, user, authInfo = authInfo))
      } yield user
      // format: on

    def delete(id: UserKey): ZIO[UserContext, IzanamiErrors, User] =
      // format: off
      for {
        _         <- AuthorizedPatterns.isAdminAllowed(id, PatternRights.D)
        deleted   <- UserDataStore.delete(id)
        user      <- jsResultToError(deleted.validate[User])
        authInfo  <- AuthInfo.authInfo
        _         <- EventStore.publish(UserDeleted(id, user, authInfo = authInfo))
      } yield user
      // format: on

    def deleteAll(patterns: Seq[String]): ZIO[UserContext, IzanamiErrors, Unit] =
      AuthInfo.isAdmin() *> UserDataStore.deleteAll(patterns)

    def getByIdWithoutPermissions(id: UserKey): RIO[UserContext, Option[User]] =
      UserDataStore.getById(id).map(_.flatMap(_.validate[User].asOpt))

    def getById(id: UserKey): ZIO[UserContext, IzanamiErrors, Option[User]] =
      AuthInfo.isAdmin() *> getByIdWithoutPermissions(id).refineOrDie[IzanamiErrors](PartialFunction.empty)

    def findByQuery(query: Query,
                    page: Int,
                    nbElementPerPage: Int): ZIO[UserContext, IzanamiErrors, PagingResult[User]] =
      AuthInfo.isAdmin() *> UserDataStore
        .findByQuery(query, page, nbElementPerPage)
        .map(jsons => JsonPagingResult(jsons))
        .refineOrDie[IzanamiErrors](PartialFunction.empty)

    def findByQuery(query: Query): ZIO[UserContext, IzanamiErrors, Source[(Key, User), NotUsed]] =
      AuthInfo.isAdmin() *> UserDataStore
        .findByQuery(query)
        .map(_.readsKV[User])
        .refineOrDie[IzanamiErrors](PartialFunction.empty)

    def countWithoutPermissions(query: Query): RIO[UserContext, Long] =
      UserDataStore.count(query)

    def count(query: Query): ZIO[UserContext, IzanamiErrors, Long] =
      AuthInfo.isAdmin() *> countWithoutPermissions(query).refineOrDie[IzanamiErrors](PartialFunction.empty)

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
}
