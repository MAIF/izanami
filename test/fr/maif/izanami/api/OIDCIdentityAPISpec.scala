package fr.maif.izanami.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import fr.maif.izanami.api.BaseAPISpec.*
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import play.api.http.Status.*
import play.api.libs.json.*
import play.api.libs.ws.{WSResponse, writeableOf_JsValue}
import play.api.test.Helpers.await

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future

class OIDCIdentityAPISpec extends BaseAPISpec {
  private val provider = new WireMockServer(WireMockConfiguration.options().dynamicPort())
  private val issuer = "https://identity.example.test"

  override def beforeAll(): Unit = {
    super.beforeAll()
    provider.start()
  }

  override def afterAll(): Unit = {
    try provider.stop()
    finally super.afterAll()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    provider.resetAll()
  }

  // Evaluate after beforeAll, once WireMock has selected its dynamic port.
  private lazy val oidcConfiguration: Map[String, AnyRef] = Map(
    "app.openid.client-id" -> "foo",
    "app.openid.client-secret" -> "bar",
    "app.openid.enabled" -> "true",
    "app.openid.method" -> "BASIC",
    "app.openid.scopes" -> "openid email profile roles",
    "app.openid.authorize-url" -> s"http://localhost:${provider.port()}/authorize",
    "app.openid.token-url" -> s"http://localhost:${provider.port()}/token",
    "app.openid.redirect-url" -> "http://localhost:9000/login",
    "app.openid.username-field" -> "name",
    "app.openid.email-field" -> "email",
    "app.openid.role-claim" -> "roles",
    "app.openid.role-right-mode" -> "initial",
    "app.openid.pkce.enabled" -> "false",
    "app.openid.right-by-roles.admin.admin" -> "true"
  )

  /** Stub the provider's ID token response for a unique authorization code,
    * then submit that code to Izanami's real OIDC callback endpoint.
    * This tests login processing, not the authorization redirect or PKCE flow.
    */
  private def loginWithMockedOidcIdentity(
      name: String,
      subject: Option[String] = Some("subject-1"),
      tokenIssuer: Option[String] = Some(issuer),
      roles: Set[String] = Set(),
      email: String = "oidc@example.test"
  ): Future[WSResponse] = {
    val code = UUID.randomUUID().toString
    val now = Instant.now().getEpochSecond
    val claim = JwtClaim(
      content = Json.obj("name" -> name, "email" -> email, "roles" -> roles).toString(),
      issuer = tokenIssuer,
      subject = subject,
      issuedAt = Some(now),
      expiration = Some(now + 300)
    )
    provider.stubFor(post(urlEqualTo("/token"))
      .withRequestBody(containing(s"code=$code"))
      .willReturn(aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(Json.obj("id_token" -> JwtJson.encode(claim, "test-signing-key", JwtAlgorithm.HS256)).toString())))
    ws.url(s"$ADMIN_BASE_URL/openid-connect-callback").post(Json.obj("code" -> code))
  }

  private def createUnlinkedOidcUser(username: String): WSResponse = {
    val response = await(loginWithMockedOidcIdentity(username, subject = Some(s"legacy-$username")))
    response.status mustBe NO_CONTENT
    // Only fixture setup uses SQL: the current API cannot create an OIDC
    // account in the historical, unlinked state that predates this migration.
    executeInDatabase { conn =>
      val statement = conn.prepareStatement(
        "UPDATE izanami.users SET oidc_issuer=NULL, oidc_subject=NULL WHERE username=? AND user_type='OIDC'"
      )
      try {
        statement.setString(1, username)
        statement.executeUpdate() mustBe 1
      } finally statement.close()
    }
    response
  }

  "OIDC identity" should {
    "persist the identity of a newly created user" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .loggedInWithAdminRights()
        .build()
      val response = await(loginWithMockedOidcIdentity("new-user"))
      response.status mustBe NO_CONTENT
      val rights = admin.copy(cookies = response.cookies.toSeq).fetchUserRights()
      rights.status mustBe OK
      (rights.json.get \ "username").as[String] mustBe "new-user"
      (rights.json.get \ "userType").as[String] mustBe "OIDC"
      val renamed = await(loginWithMockedOidcIdentity("renamed-user"))
      renamed.status mustBe NO_CONTENT
      val renamedRights = admin.copy(cookies = renamed.cookies.toSeq).fetchUserRights()
      renamedRights.status mustBe OK
      (renamedRights.json.get \ "username").as[String] mustBe "new-user"
      await(loginWithMockedOidcIdentity("new-user", subject = Some("other-subject"))).status mustBe FORBIDDEN
      val users = admin.fetchUsers()
      users.status mustBe OK
      users.json.get.as[Seq[JsValue]].map(json => (json \ "username").as[String]).toSet mustBe
        Set(ALL_RIGHTS_USERNAME, "new-user")
    }

    "progressively link a legacy OIDC user and preserve existing rights" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .loggedInWithAdminRights()
        .build()
      createUnlinkedOidcUser("legacy-user")
      admin.updateUserRights("legacy-user", admin = true, rights = TestRights()).status mustBe NO_CONTENT
      val before = admin.fetchUser("legacy-user")
      before.status mustBe OK
      val response = await(loginWithMockedOidcIdentity("legacy-user"))
      response.status mustBe NO_CONTENT
      val rights = admin.copy(cookies = response.cookies.toSeq).fetchUserRights()
      rights.status mustBe OK
      (rights.json.get \ "username").as[String] mustBe "legacy-user"
      (rights.json.get \ "admin").as[Boolean] mustBe true
      val after = admin.fetchUser("legacy-user")
      after.status mustBe OK
      after.json.get mustBe before.json.get
      val renamed = await(loginWithMockedOidcIdentity("renamed-user"))
      renamed.status mustBe NO_CONTENT
      val renamedRights = admin.copy(cookies = renamed.cookies.toSeq).fetchUserRights()
      renamedRights.status mustBe OK
      (renamedRights.json.get \ "username").as[String] mustBe "legacy-user"
      await(loginWithMockedOidcIdentity("legacy-user", subject = Some("other-subject"))).status mustBe FORBIDDEN
    }

    "resolve a linked identity despite a changed name and email" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .withUsers(TestUser("other-user"))
        .loggedInWithAdminRights()
        .build()
      val before = admin.fetchUser("other-user")
      before.status mustBe OK
      await(loginWithMockedOidcIdentity("original-user")).status mustBe NO_CONTENT
      val response = await(loginWithMockedOidcIdentity("other-user", email = "changed@example.test"))
      response.status mustBe NO_CONTENT
      val rights = admin.copy(cookies = response.cookies.toSeq).fetchUserRights()
      rights.status mustBe OK
      (rights.json.get \ "username").as[String] mustBe "original-user"
      val after = admin.fetchUser("other-user")
      after.status mustBe OK
      after.json.get mustBe before.json.get
    }

    "reject a different subject claiming a linked name without changing rights or invalidating the legitimate session" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .loggedInWithAdminRights()
        .build()
      val legitimate = await(loginWithMockedOidcIdentity("linked-user"))
      legitimate.status mustBe NO_CONTENT
      val before = admin.fetchUser("linked-user")
      before.status mustBe OK
      val response = await(loginWithMockedOidcIdentity("linked-user", subject = Some("attacker"), roles = Set("admin")))
      response.status mustBe FORBIDDEN
      response.cookies.find(_.name == "token") mustBe None
      val after = admin.fetchUser("linked-user")
      after.status mustBe OK
      after.json.get mustBe before.json.get
      val legitimateRights = admin.copy(cookies = legitimate.cookies.toSeq).fetchUserRights()
      legitimateRights.status mustBe OK
      legitimateRights.json.get mustBe before.json.get
    }

    "reject a different issuer claiming the same subject and linked name" in {
      val situation = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .build()
      val legitimate = await(loginWithMockedOidcIdentity("linked-user"))
      legitimate.status mustBe NO_CONTENT
      val before = situation.copy(cookies = legitimate.cookies.toSeq).fetchUserRights()
      before.status mustBe OK
      val rejected = await(loginWithMockedOidcIdentity("linked-user", tokenIssuer = Some(issuer + "/")))
      rejected.status mustBe FORBIDDEN
      rejected.cookies.find(_.name == "token") mustBe None
      val after = situation.copy(cookies = legitimate.cookies.toSeq).fetchUserRights()
      after.status mustBe OK
      after.json.get mustBe before.json.get
    }

    "distinguish identical subjects from different issuers" in {
      val situation = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .build()
      val first = await(loginWithMockedOidcIdentity("first-user"))
      first.status mustBe NO_CONTENT
      val response = await(loginWithMockedOidcIdentity("second-user", tokenIssuer = Some("https://other.example.test"), email = "second@example.test"))
      response.status mustBe NO_CONTENT
      val renamedFirst = await(loginWithMockedOidcIdentity("renamed-first"))
      renamedFirst.status mustBe NO_CONTENT
      val renamedSecond = await(loginWithMockedOidcIdentity("renamed-second", tokenIssuer = Some("https://other.example.test")))
      renamedSecond.status mustBe NO_CONTENT
      Seq((first, "first-user"), (response, "second-user"),
        (renamedFirst, "first-user"), (renamedSecond, "second-user")).foreach { (loginResponse, username) =>
        val rights = situation.copy(cookies = loginResponse.cookies.toSeq).fetchUserRights()
        rights.status mustBe OK
        (rights.json.get \ "username").as[String] mustBe username
      }
    }

    "reject collisions with internal and Otoroshi accounts" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .withUsers(TestUser("internal-user").withAdminRights, TestUser("otoroshi-user"))
        .loggedInWithAdminRights()
        .build()
      // There is no public endpoint for creating an Otoroshi account.
      executeInDatabase { conn =>
        val statement = conn.createStatement()
        try statement.executeUpdate("UPDATE izanami.users SET user_type='OTOROSHI' WHERE username='otoroshi-user'")
        finally statement.close()
      }
      Seq("internal-user", "otoroshi-user").foreach { name =>
        val before = admin.fetchUser(name)
        before.status mustBe OK
        val rejected = await(loginWithMockedOidcIdentity(name, roles = Set("admin")))
        rejected.status mustBe FORBIDDEN
        rejected.cookies.find(_.name == "token") mustBe None
        val after = admin.fetchUser(name)
        after.status mustBe OK
        after.json.get mustBe before.json.get
      }
      val internalSession = admin.loggedAs("internal-user", "barbar123").fetchUserRights()
      internalSession.status mustBe OK
      (internalSession.json.get \ "username").as[String] mustBe "internal-user"
    }

    "reject missing or empty issuer and subject before linking a legacy user" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .loggedInWithAdminRights()
        .build()
      createUnlinkedOidcUser("legacy-user")
      val before = admin.fetchUser("legacy-user")
      before.status mustBe OK
      Seq((None, Some("subject-1")), (Some(""), Some("subject-1")),
        (Some(issuer), None), (Some(issuer), Some(""))).foreach { (iss, sub) =>
        val response = await(loginWithMockedOidcIdentity("legacy-user", tokenIssuer = iss, subject = sub))
        response.status must be >= 400
        response.cookies.find(_.name == "token") mustBe None
      }
      val after = admin.fetchUser("legacy-user")
      after.status mustBe OK
      after.json.get mustBe before.json.get
      val valid = await(loginWithMockedOidcIdentity("legacy-user", subject = Some("valid-subject")))
      valid.status mustBe NO_CONTENT
      val validRights = admin.copy(cookies = valid.cookies.toSeq).fetchUserRights()
      validRights.status mustBe OK
      (validRights.json.get \ "username").as[String] mustBe "legacy-user"
      await(loginWithMockedOidcIdentity("legacy-user", subject = Some("other-subject"))).status mustBe FORBIDDEN
    }

    "preserve a single account during concurrent creation for the same identity" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .loggedInWithAdminRights()
        .build()
      val responses = await(Future.sequence(Seq(
        loginWithMockedOidcIdentity("new-user", email = "new@example.test"),
        loginWithMockedOidcIdentity("renamed-user", email = "renamed@example.test")
      )))
      val successful = responses.filter(_.status == NO_CONTENT)
      successful must not be empty
      responses.filterNot(_.status == NO_CONTENT).foreach { response =>
        response.status must be >= 400
        response.cookies.find(_.name == "token") mustBe None
      }
      val usernames = successful.map { response =>
        val rights = admin.copy(cookies = response.cookies.toSeq).fetchUserRights()
        rights.status mustBe OK
        (rights.json.get \ "username").as[String]
      }.toSet
      usernames.size mustBe 1
      val users = admin.fetchUsers()
      users.status mustBe OK
      val listed = users.json.get.as[Seq[JsValue]].map(json => (json \ "username").as[String]).toSet
      listed.intersect(Set("new-user", "renamed-user")) mustBe usernames
      listed mustBe usernames + ALL_RIGHTS_USERNAME
    }

    "allow only the first identity to claim a legacy name concurrently" in {
      val situation = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .build()
      createUnlinkedOidcUser("legacy-user")
      val subjects = Seq("subject-a", "subject-b")
      val responses = await(Future.sequence(subjects.map(subject => loginWithMockedOidcIdentity("legacy-user", subject = Some(subject)))))
      responses.map(_.status).sorted mustBe Seq(NO_CONTENT, FORBIDDEN).sorted
      responses.zip(subjects).foreach { (response, subject) =>
        val retried = await(loginWithMockedOidcIdentity("legacy-user", subject = Some(subject)))
        retried.status mustBe response.status
        if (response.status == NO_CONTENT) {
          Seq(response, retried).foreach { loginResponse =>
            val rights = situation.copy(cookies = loginResponse.cookies.toSeq).fetchUserRights()
            rights.status mustBe OK
            (rights.json.get \ "username").as[String] mustBe "legacy-user"
          }
        } else {
          response.cookies.find(_.name == "token") mustBe None
          retried.cookies.find(_.name == "token") mustBe None
        }
      }
    }

    "reject concurrent creation conflicts for different identities claiming the same name" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .loggedInWithAdminRights()
        .build()
      val subjects = Seq("subject-a", "subject-b")
      val responses = await(Future.sequence(subjects.map(subject => loginWithMockedOidcIdentity("new-user", subject = Some(subject)))))
      responses.count(_.status == NO_CONTENT) mustBe 1
      val rejected = responses.find(_.status != NO_CONTENT).get
      rejected.status must be >= 400
      rejected.cookies.find(_.name == "token") mustBe None
      val users = admin.fetchUsers()
      users.status mustBe OK
      users.json.get.as[Seq[JsValue]].map(json => (json \ "username").as[String]).toSet mustBe
        Set(ALL_RIGHTS_USERNAME, "new-user")
      responses.zip(subjects).foreach { (response, subject) =>
        val retried = await(loginWithMockedOidcIdentity("new-user", subject = Some(subject)))
        if (response.status == NO_CONTENT) {
          retried.status mustBe NO_CONTENT
          Seq(response, retried).foreach { loginResponse =>
            val rights = admin.copy(cookies = loginResponse.cookies.toSeq).fetchUserRights()
            rights.status mustBe OK
            (rights.json.get \ "username").as[String] mustBe "new-user"
          }
        } else {
          retried.status mustBe FORBIDDEN
          retried.cookies.find(_.name == "token") mustBe None
        }
      }
    }

    "handle concurrent logins without exhausting transaction connections" in {
      val situation = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration)
        .build()
      await(loginWithMockedOidcIdentity("linked-user")).status mustBe NO_CONTENT
      val responses = await(Future.sequence((1 to 24).map(_ => loginWithMockedOidcIdentity("linked-user"))))
      responses.foreach { response =>
        response.status mustBe NO_CONTENT
        val rights = situation.copy(cookies = response.cookies.toSeq).fetchUserRights()
        rights.status mustBe OK
        (rights.json.get \ "username").as[String] mustBe "linked-user"
      }
    }

    "roll back identity and roles when rights compliance rejects the login" in {
      val admin = TestSituationBuilder()
        .withCustomConfiguration(oidcConfiguration ++ Map(
          "app.openid.role-right-mode" -> "supervised",
          "app.openid.right-by-roles.admin.admin-allowed" -> "false"
        ))
        .loggedInWithAdminRights()
        .build()
      val original = createUnlinkedOidcUser("legacy-user")
      val before = admin.fetchUser("legacy-user")
      before.status mustBe OK
      val response = await(loginWithMockedOidcIdentity("legacy-user", subject = Some("rejected-subject"), roles = Set("admin")))
      response.status mustBe BAD_REQUEST
      response.cookies.find(_.name == "token") mustBe None
      val after = admin.fetchUser("legacy-user")
      after.status mustBe OK
      after.json.get mustBe before.json.get
      val originalRights = admin.copy(cookies = original.cookies.toSeq).fetchUserRights()
      originalRights.status mustBe OK
      originalRights.json.get mustBe before.json.get
      // Another identity can still claim the legacy name: the rejected
      // transaction must not have persisted its binding.
      val accepted = await(loginWithMockedOidcIdentity("legacy-user", subject = Some("accepted-subject")))
      accepted.status mustBe NO_CONTENT
      val acceptedRights = admin.copy(cookies = accepted.cookies.toSeq).fetchUserRights()
      acceptedRights.status mustBe OK
      (acceptedRights.json.get \ "username").as[String] mustBe "legacy-user"
      await(loginWithMockedOidcIdentity("legacy-user", subject = Some("rejected-subject"))).status mustBe FORBIDDEN
    }
  }
}
