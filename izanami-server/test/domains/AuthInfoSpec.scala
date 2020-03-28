package domains

import cats.data.NonEmptyList
import domains.errors.{IzanamiErrors, Unauthorized}
import domains.user.OauthUser
import libs.logs.ZLogger
import test.IzanamiSpec
import zio.{Layer, Runtime, ZEnv, ZLayer}
import domains.auth.AuthInfo

class AuthInfoSpec extends IzanamiSpec {

  def testLayer(admin: Boolean = false): Layer[Throwable, AuthInfo] = AuthInfo.value(
    OauthUser("1",
              "john.doe",
              "john.doe@gmail.fr",
              admin,
              AuthorizedPatterns(AuthorizedPattern("test", PatternRights.R)))
  )

  "AuthInfo" must {
    "is admin" in {

      val authModule: ZLayer[Any, Throwable, AuthInfo with ZLogger] = (testLayer(true) ++ ZLogger.live)

      val res: Either[IzanamiErrors, Unit] =
        Runtime.default.unsafeRun(AuthInfo.isAdmin().either.provideSomeLayer[ZEnv](authModule))
      res must be(Right(()))
    }

    "is not admin" in {
      val authModule: ZLayer[Any, Throwable, AuthInfo with ZLogger] = (testLayer() ++ ZLogger.live)

      val res: Either[IzanamiErrors, Unit] =
        Runtime.default.unsafeRun(AuthInfo.isAdmin().either.provideSomeLayer[ZEnv](authModule))
      res must be(Left(NonEmptyList.of(Unauthorized(None))))
    }
  }
}
