package fr.maif.izanami.services

import fr.maif.izanami.datastores.{
  PersonnalAccessTokenDatastore,
  UsersDatastore
}
import fr.maif.izanami.models.*
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterFutureEither

import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContext, Future}

class AuthService(
    personalAccessTokenDatastore: => PersonnalAccessTokenDatastore,
    userDatastore: => UsersDatastore,
    tokenSecret: String,
    encryptionKey: SecretKeySpec
)(implicit val ec: ExecutionContext) {
  val decryptionStuff = DecryptionStuff(tokenSecret, encryptionKey)

  def checkPersonalAccessToken(
      username: String,
      token: String
  ): Future[Option[ReadPersonnalAccessToken]] =
    personalAccessTokenDatastore.readAccessToken(
      username = username,
      token = token
    )

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
  def checkAdminSession(session: String): FutureEither[String] = {
    userDatastore.checkAdminSession(session)
  }

  def findSession(session: String): Future[Option[String]] = {
    userDatastore.findSession(session)
  }

  def findSessionWithRightForTenant(
      session: String,
      tenant: String
  ): FutureEither[UserWithCompleteRightForOneTenant] = {
    userDatastore.findSessionWithRightForTenant(session, tenant)
  }

  def findUserWithRightForTenant(
      username: String,
      tenant: String
  ): FutureEither[Option[UserWithCompleteRightForOneTenant]] = {
    userDatastore.findUserWithRightForTenant(
      username = username,
      tenant = tenant
    ).toFEither
      .map(u => Some(u))
  }

  def findCompleteRightsFromTenant(
      username: String,
      tenants: Set[String]
  ): Future[Option[UserWithRights]] =
    userDatastore.findCompleteRightsFromTenant(
      username = username,
      tenants = tenants
    )
}

case class DecryptionStuff(tokenSecret: String, encryptionKey: SecretKeySpec)
