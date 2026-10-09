package fr.maif.izanami.services

import fr.maif.izanami.models.{ReadPersonnalAccessToken, User, UserWithCompleteRightForOneTenant, UserWithRights, UserWithTenantRights}
import fr.maif.izanami.datastores.{PersonnalAccessTokenDatastore, UsersDatastore}
import fr.maif.izanami.datastores.PersonnalAccessTokenDatastore.{TokenCheckFailure, TokenCheckSuccess}

import scala.concurrent.Future
import java.util.Base64
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.errors.{BadFormatPersonalAccessToken, InvalidpersonalAccessToken}
import fr.maif.izanami.utils.Done

import scala.concurrent.ExecutionContext
import javax.crypto.spec.SecretKeySpec
import fr.maif.izanami.utils.syntax.implicits.BetterFutureEither



class AuthService(
  personalAccessTokenDatastore: =>PersonnalAccessTokenDatastore,
  userDatastore: =>UsersDatastore,
  tokenSecret: String,
  encryptionKey: SecretKeySpec
)(implicit val ec: ExecutionContext) {
  val decryptionStuff = DecryptionStuff(tokenSecret, encryptionKey)


  def checkPersonalAccessToken(username: String, token: String): Future[Option[ReadPersonnalAccessToken]] = personalAccessTokenDatastore.readAccessToken(username = username, token = token)

  def isUserValid(username: String, password: String): Future[Option[User]] = {
    userDatastore.isUserValid(username = username, password = password)
  }

  def findUser(username: String): Future[Option[UserWithTenantRights]] = {
    userDatastore.findUser(username)
  }

  def findSessionWithTenantRights(
      session: String
  ): Future[Option[UserWithTenantRights]] = {
    userDatastore.findSessionWithTenantRights(session)
  }

  def findSessionWithCompleteRights(
      session: String
  ): Future[Option[UserWithRights]] = {
    userDatastore.findSessionWithCompleteRights(session)
  }
  def findAdminSession(session: String): Future[Option[String]] = {
    userDatastore.findAdminSession(session)
  }

  def findSession(session: String): Future[Option[String]] = {
    userDatastore.findSession(session)
  }

  def findSessionWithRightForTenant(
      session: String,
      tenant: String
  ): Future[Option[UserWithCompleteRightForOneTenant]] = {
    userDatastore.findSessionWithRightForTenant(session, tenant).map(_.toOption)
  }

  def findUserWithRightForTenant(
      username: String,
      tenant: String
  ): FutureEither[Option[UserWithCompleteRightForOneTenant]] = {
    userDatastore.findUserWithRightForTenant(username = username, tenant = tenant).toFEither
      .map(u => Some(u))
  }

  def findCompleteRightsFromTenant(
      username: String,
      tenants: Set[String]
  ): Future[Option[UserWithRights]] =
    userDatastore.findCompleteRightsFromTenant(username = username, tenants = tenants)
}

case class DecryptionStuff(tokenSecret: String, encryptionKey: SecretKeySpec)
