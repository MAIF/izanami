package fr.maif.izanami.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import fr.maif.izanami.api.BaseAPISpec.*
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import pdi.jwt.algorithms.JwtAsymmetricAlgorithm
import play.api.http.Status.*
import play.api.libs.json.*
import play.api.libs.ws.{WSResponse, writeableOf_JsValue}
import play.api.test.Helpers.await

import java.time.Instant
import java.util.UUID
import java.util.Base64
import java.security.{KeyPairGenerator, PrivateKey}
import java.security.interfaces.RSAPublicKey
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

  private lazy val signingKeyPair = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()
  }
  private lazy val jwks = {
    val publicKey = signingKeyPair.getPublic.asInstanceOf[RSAPublicKey]
    Json.obj("keys" -> Json.arr(Json.obj(
      "kid" -> "test-key", "kty" -> "RSA", "use" -> "sig", "alg" -> "RS256",
      "n" -> Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getModulus.toByteArray.dropWhile(_ == 0)),
      "e" -> Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getPublicExponent.toByteArray)
    )))
  }
  private lazy val validatedOidcConfiguration = oidcConfiguration ++ Map(
    "app.openid.issuer" -> issuer,
    "app.openid.audience" -> "foo",
    "app.openid.jwks-url" -> s"http://localhost:${provider.port()}/jwks"
  )

  /** Start a browser-bound login, stub the provider's ID token response for a
    * unique authorization code, then submit that code and state to Izanami.
    * The provider's interactive authorization page is not simulated here.
    */
  private def loginWithMockedOidcIdentity(
      name: String,
      subject: Option[String] = Some("subject-1"),
      tokenIssuer: Option[String] = Some(issuer),
      roles: Set[String] = Set(),
      email: String = "oidc@example.test",
      tokenClaims: JsObject = Json.obj(),
      signingKey: Option[PrivateKey] = None,
      kid: String = "test-key",
      callbackState: Option[String] = None,
      includeSession: Boolean = true,
      signingAlgorithm: JwtAsymmetricAlgorithm = JwtAlgorithm.RS256
  ): Future[WSResponse] = {
    val start = await(ws.url(s"$ADMIN_BASE_URL/openid-connect").withFollowRedirects(false).get())
    val parameters = org.apache.pekko.http.scaladsl.model.Uri(start.header("Location").get).query()
    val state = parameters.get("state").get
    val nonce = parameters.get("nonce").get
    val code = UUID.randomUUID().toString
    val now = Instant.now().getEpochSecond
    val claim = JwtClaim(
      content = Json.obj("name" -> name, "email" -> email, "roles" -> roles, "nonce" -> nonce).toString(),
      issuer = tokenIssuer,
      subject = subject,
      audience = Some(Set("foo")),
      issuedAt = Some(now),
      expiration = Some(now + 300)
    )
    val claims = Json.parse(claim.toJson).as[JsObject] ++ tokenClaims
    val token = signingKey.fold(JwtJson.encode(claims.toString, "test-signing-key", JwtAlgorithm.HS256)) { key =>
      JwtJson.encode(Json.obj("alg" -> signingAlgorithm.name, "kid" -> kid).toString, claims.toString, key, signingAlgorithm)
    }
    provider.stubFor(post(urlEqualTo("/token"))
      .withRequestBody(containing(s"code=$code"))
      .willReturn(aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(Json.obj("id_token" -> token).toString())))
    ws.url(s"$ADMIN_BASE_URL/openid-connect-callback")
      .withCookies((if (includeSession) start.cookies.toArray else Array.empty[play.api.libs.ws.WSCookie]): _*)
      .post(Json.obj("code" -> code, "state" -> callbackState.getOrElse(state)))
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

  "OIDC validation" should {
    "keep legacy configuration working and warn about missing token validation settings" in {
      TestSituationBuilder().withCustomConfiguration(oidcConfiguration).build()
      val logger = org.slf4j.LoggerFactory.getLogger("izanami.oidc.validation").asInstanceOf[ch.qos.logback.classic.Logger]
      val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]()
      val oldLevel = logger.getLevel
      appender.start()
      logger.setLevel(ch.qos.logback.classic.Level.WARN)
      logger.addAppender(appender)
      try {
        val response = await(loginWithMockedOidcIdentity("legacy-login", tokenClaims = Json.obj("aud" -> "other-app")))
        response.status mustBe NO_CONTENT
        import scala.jdk.CollectionConverters.*
        appender.list.asScala.exists(event => event.getFormattedMessage.contains("OIDC compatibility mode") &&
          event.getFormattedMessage.contains("issuer") && event.getFormattedMessage.contains("audience") &&
          event.getFormattedMessage.contains("signature verification")) mustBe true
      } finally {
        logger.detachAppender(appender)
        logger.setLevel(oldLevel)
        appender.stop()
      }
    }

    "validate a signed token and cache the trusted JWKS" in {
      val situation = TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration).build()
      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(jwks.toString)))
      val first = await(loginWithMockedOidcIdentity("signed-user", signingKey = Some(signingKeyPair.getPrivate)))
      first.status mustBe NO_CONTENT
      val rights = situation.copy(cookies = first.cookies.toSeq).fetchUserRights()
      rights.status mustBe OK
      (rights.json.get \ "username").as[String] mustBe "signed-user"
      await(loginWithMockedOidcIdentity("renamed-user", signingKey = Some(signingKeyPair.getPrivate))).status mustBe NO_CONTENT
      provider.verify(1, getRequestedFor(urlEqualTo("/jwks")))
    }

    "select typed JWKS signing keys without accepting incompatible or ambiguous keys" in {
      val rsaKey = (jwks \ "keys").as[Seq[JsObject]].head
      val cases = Seq(
        (Json.arr(rsaKey - "alg"), NO_CONTENT),
        (Json.arr(
          Json.obj("kty" -> "oct", "kid" -> "symmetric-key"),
          Json.obj("kty" -> "EC", "crv" -> "unsupported"),
          Json.obj("kty" -> "RSA", "kid" -> "malformed-key"),
          Json.obj("kty" -> "RSA", "use" -> "enc"),
          Json.obj("kty" -> "RSA", "key_ops" -> Json.arr("encrypt")),
          rsaKey ++ Json.obj("key_ops" -> Json.arr("verify"))
        ), NO_CONTENT),
        (Json.arr(rsaKey, rsaKey), UNAUTHORIZED),
        (Json.arr(rsaKey ++ Json.obj("alg" -> "RS512")), UNAUTHORIZED),
        (Json.arr(rsaKey ++ Json.obj("use" -> "enc")), UNAUTHORIZED),
        (Json.arr(rsaKey ++ Json.obj("key_ops" -> Json.arr("encrypt"))), UNAUTHORIZED),
        (Json.arr(rsaKey ++ Json.obj("n" -> "AQAB")), UNAUTHORIZED)
      )
      cases.foreach { (keys, expectedStatus) =>
        TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration).build()
        provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(Json.obj("keys" -> keys).toString)))
        val response = await(loginWithMockedOidcIdentity("typed-key-user", signingKey = Some(signingKeyPair.getPrivate)))
        response.status mustBe expectedStatus
        if (expectedStatus == UNAUTHORIZED) {
          response.cookies.find(_.name == "token") mustBe None
        }
      }
    }

    "reject invalid signatures and unsupported algorithms without falling back" in {
      val admin = TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration)
        .loggedInWithAdminRights().build()
      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(jwks.toString)))
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      val attackerKey = generator.generateKeyPair().getPrivate
      Seq(None, Some(attackerKey)).foreach { key =>
        val response = await(loginWithMockedOidcIdentity("attacker", roles = Set("admin"), signingKey = key))
        response.status mustBe UNAUTHORIZED
        response.cookies.find(_.name == "token") mustBe None
      }
      val users = admin.fetchUsers()
      users.status mustBe OK
      users.json.get.as[Seq[JsValue]].map(json => (json \ "username").as[String]) mustBe Seq(ALL_RIGHTS_USERNAME)
    }

    "verify ECDSA tokens using the matching JWKS curve" in {
      TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration).build()
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"))
      val keyPair = generator.generateKeyPair()
      val publicKey = keyPair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey]
      val ecJwks = Json.obj("keys" -> Json.arr(Json.obj(
        "kid" -> "ec-key", "kty" -> "EC", "crv" -> "P-256", "alg" -> "ES256", "use" -> "sig",
        "x" -> Base64.getUrlEncoder.withoutPadding().encodeToString(
          (Array.fill[Byte](32)(0) ++ publicKey.getW.getAffineX.toByteArray).takeRight(32)),
        "y" -> Base64.getUrlEncoder.withoutPadding().encodeToString(
          (Array.fill[Byte](32)(0) ++ publicKey.getW.getAffineY.toByteArray).takeRight(32))
      )))
      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(ecJwks.toString)))
      await(loginWithMockedOidcIdentity("ec-user", signingKey = Some(keyPair.getPrivate), kid = "ec-key",
        signingAlgorithm = JwtAlgorithm.ES256)).status mustBe NO_CONTENT
    }

    "reject invalid identity audience nonce and timestamps before creating a user" in {
      val admin = TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration)
        .loggedInWithAdminRights().build()
      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(jwks.toString)))
      val now = Instant.now().getEpochSecond
      val invalidClaims = Seq(
        Json.obj("iss" -> "https://other.example.test"),
        Json.obj("aud" -> "other-app"),
        Json.obj("aud" -> Json.arr("foo", "other-app")),
        Json.obj("aud" -> Json.arr("foo", "other-app"), "azp" -> "other-app"),
        Json.obj("azp" -> "other-app"),
        Json.obj("azp" -> 42),
        Json.obj("nonce" -> "wrong-nonce"),
        Json.obj("nonce" -> JsNull),
        Json.obj("exp" -> (now - 30)),
        Json.obj("exp" -> JsNull),
        Json.obj("iat" -> JsNull),
        Json.obj("iat" -> (now + 120)),
        Json.obj("nbf" -> (now + 120))
      )
      invalidClaims.foreach { claims =>
        val response = await(loginWithMockedOidcIdentity("invalid-user", signingKey = Some(signingKeyPair.getPrivate),
          tokenClaims = claims, roles = Set("admin")))
        response.status mustBe UNAUTHORIZED
        response.cookies.find(_.name == "token") mustBe None
      }
      val users = admin.fetchUsers()
      users.status mustBe OK
      users.json.get.as[Seq[JsValue]].map(json => (json \ "username").as[String]) mustBe Seq(ALL_RIGHTS_USERNAME)
      await(loginWithMockedOidcIdentity("valid-user", signingKey = Some(signingKeyPair.getPrivate),
        tokenClaims = Json.obj("aud" -> Json.arr("foo", "other-app"), "azp" -> "foo"))).status mustBe NO_CONTENT
    }

    "enforce configured issuer and audience even when JWKS is absent" in {
      TestSituationBuilder().withCustomConfiguration(oidcConfiguration ++ Map(
        "app.openid.issuer" -> issuer, "app.openid.audience" -> "foo"
      )).build()
      await(loginWithMockedOidcIdentity("wrong-issuer", tokenIssuer = Some("https://other.example.test"))).status mustBe UNAUTHORIZED
      await(loginWithMockedOidcIdentity("wrong-audience", tokenClaims = Json.obj("aud" -> "other-app"))).status mustBe UNAUTHORIZED
      await(loginWithMockedOidcIdentity("valid-user")).status mustBe NO_CONTENT
    }

    "verify signatures even when issuer and audience are not configured" in {
      TestSituationBuilder().withCustomConfiguration(oidcConfiguration ++ Map(
        "app.openid.jwks-url" -> s"http://localhost:${provider.port()}/jwks"
      )).build()
      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(jwks.toString)))
      await(loginWithMockedOidcIdentity("unsigned-user")).status mustBe UNAUTHORIZED
      await(loginWithMockedOidcIdentity("signed-user", signingKey = Some(signingKeyPair.getPrivate),
        tokenIssuer = Some("https://another.example.test"), tokenClaims = Json.obj("aud" -> "other-app"))).status mustBe NO_CONTENT
    }

    "reject unavailable malformed and redirected JWKS without accepting an unverified token" in {
      TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration).build()
      Seq(aResponse().withStatus(503), okJson("{}"), aResponse().withStatus(302)
        .withHeader("Location", s"http://localhost:${provider.port()}/other-jwks")).foreach { response =>
        provider.stubFor(get(urlEqualTo("/jwks")).willReturn(response))
        await(loginWithMockedOidcIdentity("invalid-user", signingKey = Some(signingKeyPair.getPrivate))).status mustBe UNAUTHORIZED
      }
      provider.verify(0, getRequestedFor(urlEqualTo("/other-jwks")))
    }

    "refresh JWKS for key rotation and throttle refresh for unknown keys" in {
      TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration).build()
      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(jwks.toString)))
      await(loginWithMockedOidcIdentity("signed-user", signingKey = Some(signingKeyPair.getPrivate))).status mustBe NO_CONTENT
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      val rotatedKeyPair = generator.generateKeyPair()
      val publicKey = rotatedKeyPair.getPublic.asInstanceOf[RSAPublicKey]
      val rotatedJwks = Json.obj("keys" -> Json.arr(Json.obj(
        "kid" -> "rotated-key", "kty" -> "RSA", "use" -> "sig", "alg" -> "RS256",
        "n" -> Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getModulus.toByteArray.dropWhile(_ == 0)),
        "e" -> Base64.getUrlEncoder.withoutPadding().encodeToString(publicKey.getPublicExponent.toByteArray)
      )))
      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(rotatedJwks.toString)))
      await(loginWithMockedOidcIdentity("signed-user", signingKey = Some(rotatedKeyPair.getPrivate), kid = "rotated-key")).status mustBe NO_CONTENT
      Seq("unknown-key-1", "unknown-key-2").foreach { kid =>
        await(loginWithMockedOidcIdentity("invalid-user", signingKey = Some(rotatedKeyPair.getPrivate), kid = kid)).status mustBe UNAUTHORIZED
      }
      provider.verify(2, getRequestedFor(urlEqualTo("/jwks")))
    }

    "keep cached signing keys usable when retrieving an unknown key fails" in {
      val situation = TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration).build()
      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(jwks.toString)))
      await(loginWithMockedOidcIdentity("signed-user", signingKey = Some(signingKeyPair.getPrivate))).status mustBe NO_CONTENT

      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse().withStatus(503)))
      val rejected = await(loginWithMockedOidcIdentity("signed-user", signingKey = Some(signingKeyPair.getPrivate), kid = "unknown-key"))
      rejected.status mustBe UNAUTHORIZED
      rejected.cookies.find(_.name == "token") mustBe None

      val accepted = await(loginWithMockedOidcIdentity("signed-user", signingKey = Some(signingKeyPair.getPrivate)))
      accepted.status mustBe NO_CONTENT
      val rights = situation.copy(cookies = accepted.cookies.toSeq).fetchUserRights()
      rights.status mustBe OK
      (rights.json.get \ "username").as[String] mustBe "signed-user"

      await(loginWithMockedOidcIdentity("signed-user", signingKey = Some(signingKeyPair.getPrivate), kid = "other-unknown-key")).status mustBe UNAUTHORIZED
      provider.verify(2, getRequestedFor(urlEqualTo("/jwks")))
    }

    "expire preserved signing keys at the original deadline after failed refreshes" in {
      val admin = TestSituationBuilder().withCustomConfiguration(validatedOidcConfiguration)
        .loggedInWithAdminRights().build()
      val settings = admin.fetchConfiguration()
      settings.status mustBe OK
      val configuration = (settings.json.get \ "oidcConfiguration")
        .as[fr.maif.izanami.models.OAuth2Configuration](fr.maif.izanami.models.OAuth2Configuration._fmt)
      // Advance only the cache clock: no SQL, sleeps or five-minute test wait.
      val nanos = new java.util.concurrent.atomic.AtomicLong(0L)
      val validator = new fr.maif.izanami.security.OidcTokenValidator(ws, ticker = () => nanos.get())
      val now = Instant.now().getEpochSecond
      val claim = JwtClaim(content = Json.obj("nonce" -> "test-nonce").toString,
        issuer = Some(issuer), subject = Some("subject-1"), audience = Some(Set("foo")),
        issuedAt = Some(now), expiration = Some(now + 300))
      val token = JwtJson.encode(Json.obj("alg" -> "RS256", "kid" -> "test-key").toString,
        claim.toJson, signingKeyPair.getPrivate, JwtAlgorithm.RS256)
      val unknownKeyToken = JwtJson.encode(Json.obj("alg" -> "RS256", "kid" -> "unknown-key").toString,
        claim.toJson, signingKeyPair.getPrivate, JwtAlgorithm.RS256)

      provider.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(jwks.toString)))
      await(validator.validate(token, configuration, "test-nonce")).isRight mustBe true
      nanos.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(4))

      Seq(aResponse().withStatus(503), okJson("{}")).foreach { failure =>
        provider.stubFor(get(urlEqualTo("/jwks")).willReturn(failure))
        await(validator.validate(unknownKeyToken, configuration, "test-nonce")).isLeft mustBe true
        await(validator.validate(token, configuration, "test-nonce")).isRight mustBe true
        nanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(31))
      }
      provider.verify(3, getRequestedFor(urlEqualTo("/jwks")))

      await(validator.validate(token, configuration, "test-nonce")).isLeft mustBe true
      provider.verify(4, getRequestedFor(urlEqualTo("/jwks")))
    }

    "reject callbacks without a matching browser state before contacting the token endpoint" in {
      TestSituationBuilder().withCustomConfiguration(oidcConfiguration).build()
      await(ws.url(s"$ADMIN_BASE_URL/openid-connect-callback").post(Json.obj("code" -> "code"))).status mustBe UNAUTHORIZED
      await(loginWithMockedOidcIdentity("invalid-user", callbackState = Some("wrong-state"))).status mustBe UNAUTHORIZED
      await(loginWithMockedOidcIdentity("invalid-user", includeSession = false)).status mustBe UNAUTHORIZED
      provider.verify(0, postRequestedFor(urlEqualTo("/token")))
    }

    "consume state once even if the caller replays the original session cookie" in {
      TestSituationBuilder().withCustomConfiguration(oidcConfiguration).build()
      val start = await(ws.url(s"$ADMIN_BASE_URL/openid-connect").withFollowRedirects(false).get())
      val parameters = org.apache.pekko.http.scaladsl.model.Uri(start.header("Location").get).query()
      val now = Instant.now().getEpochSecond
      val claim = JwtClaim(content = Json.obj("name" -> "replay-user", "email" -> "replay@example.test",
        "nonce" -> parameters.get("nonce").get).toString(), issuer = Some(issuer), subject = Some("replay-subject"),
        issuedAt = Some(now), expiration = Some(now + 300))
      provider.stubFor(post(urlEqualTo("/token")).willReturn(okJson(Json.obj(
        "id_token" -> JwtJson.encode(claim, "test-key", JwtAlgorithm.HS256)).toString())))
      val body = Json.obj("code" -> "replay-code", "state" -> parameters.get("state").get)
      val request = ws.url(s"$ADMIN_BASE_URL/openid-connect-callback").withCookies(start.cookies.toArray: _*)
      val first = await(request.post(body))
      first.status mustBe NO_CONTENT
      first.cookies.find(_.name == "PLAY_SESSION").forall(_.value.isEmpty) mustBe true
      await(request.post(body)).status mustBe UNAUTHORIZED
      provider.verify(1, postRequestedFor(urlEqualTo("/token")))
    }

    "use the current OIDC configuration and preserve PKCE checks after configuration changes" in {
      Seq(false, true).foreach { enablePkce =>
        val admin = TestSituationBuilder().loggedInWithAdminRights().build()
        val configuration = Json.obj(
          "enabled" -> true,
          "method" -> "BASIC",
          "clientId" -> "foo",
          "clientSecret" -> "bar",
          "authorizeUrl" -> s"http://localhost:${provider.port()}/authorize",
          "tokenUrl" -> s"http://localhost:${provider.port()}/token",
          "callbackUrl" -> "http://localhost:9000/login",
          "nameField" -> "name",
          "emailField" -> "email",
          "scopes" -> "openid email profile",
          "pkce" -> Json.obj("enabled" -> false, "algorithm" -> "S256")
        )
        admin.updateConfiguration(oidcConfiguration = configuration).status mustBe NO_CONTENT
        val start = await(ws.url(s"$ADMIN_BASE_URL/openid-connect").withFollowRedirects(false).get())
        start.status mustBe SEE_OTHER
        val parameters = org.apache.pekko.http.scaladsl.model.Uri(start.header("Location").get).query()
        val updated = configuration ++ Json.obj(
          "clientSecret" -> "new-secret",
          "pkce" -> Json.obj("enabled" -> enablePkce, "algorithm" -> "S256")
        )
        admin.updateConfiguration(oidcConfiguration = updated).status mustBe NO_CONTENT
        provider.resetAll()
        val now = Instant.now().getEpochSecond
        val claim = JwtClaim(
          content = Json.obj("name" -> "configuration-user", "email" -> "configuration@example.test",
            "nonce" -> parameters.get("nonce").get).toString,
          issuer = Some(issuer), subject = Some("configuration-subject"),
          issuedAt = Some(now), expiration = Some(now + 300)
        )
        provider.stubFor(post(urlEqualTo("/token"))
          .withBasicAuth("foo", "new-secret")
          .willReturn(okJson(Json.obj("id_token" -> JwtJson.encode(claim, "test-key", JwtAlgorithm.HS256)).toString)))
        val response = await(ws.url(s"$ADMIN_BASE_URL/openid-connect-callback")
          .withCookies(start.cookies.toArray: _*)
          .post(Json.obj("code" -> "configuration-code", "state" -> parameters.get("state").get)))
        response.status mustBe (if (enablePkce) UNAUTHORIZED else NO_CONTENT)
        provider.verify(if (enablePkce) 0 else 1, postRequestedFor(urlEqualTo("/token")))
        if (enablePkce) {
          response.cookies.find(_.name == "token") mustBe None
        } else {
          val rights = admin.copy(cookies = response.cookies.toSeq).fetchUserRights()
          rights.status mustBe OK
          (rights.json.get \ "username").as[String] mustBe "configuration-user"
        }
      }
    }

    "send the PKCE verifier generated for the browser login attempt" in {
      TestSituationBuilder().withCustomConfiguration(oidcConfiguration + ("app.openid.pkce.enabled" -> "true")).build()
      await(loginWithMockedOidcIdentity("pkce-user")).status mustBe NO_CONTENT
      provider.verify(1, postRequestedFor(urlEqualTo("/token")).withRequestBody(matching(".*code_verifier=[A-Za-z0-9_-]+.*")))
    }
  }
}
