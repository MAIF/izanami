package fr.maif.izanami.datastores

import fr.maif.izanami.api.BaseAPISpec
import fr.maif.izanami.api.BaseAPISpec.*

import java.sql.SQLException

class OIDCIdentityPersistenceSpec extends BaseAPISpec {
  "OIDC identity persistence" should {
    "enforce identity consistency and uniqueness in the database" in {
      TestSituationBuilder()
        .withUsers(TestUser("internal-user"), TestUser("unlinked-user"), TestUser("linked-user"))
        .build()

      executeInDatabase { conn =>
        val setup = conn.prepareStatement(
          """UPDATE izanami.users SET user_type='OIDC',
            |oidc_issuer=CASE WHEN username='linked-user' THEN ? END,
            |oidc_subject=CASE WHEN username='linked-user' THEN ? END
            |WHERE username IN ('unlinked-user', 'linked-user')""".stripMargin
        )
        try {
          setup.setString(1, "https://identity.example.test")
          setup.setString(2, "subject-1")
          setup.executeUpdate() mustBe 2
        } finally setup.close()

        val invalidBindings = Seq(
          ("unlinked-user", "issuer", null, "23514"),
          ("unlinked-user", null, "subject", "23514"),
          ("unlinked-user", "", "subject", "23514"),
          ("unlinked-user", "issuer", "", "23514"),
          ("internal-user", "issuer", "subject", "23514"),
          ("unlinked-user", "https://identity.example.test", "subject-1", "23505")
        )
        invalidBindings.foreach { (username, issuer, subject, sqlState) =>
          val statement = conn.prepareStatement(
            "UPDATE izanami.users SET oidc_issuer=?, oidc_subject=? WHERE username=?"
          )
          try {
            statement.setString(1, issuer)
            statement.setString(2, subject)
            statement.setString(3, username)
            val error = intercept[SQLException] { statement.executeUpdate() }
            error.getSQLState mustBe sqlState
          } finally statement.close()
        }
      }
    }
  }
}
