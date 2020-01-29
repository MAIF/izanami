package domains

import cats.data.NonEmptyList
import domains.errors.{IzanamiErrors, Unauthorized}
import domains.user.OauthUser
import libs.logs.{Logger, LoggerModule, ProdLogger}
import test.IzanamiSpec
import zio.Runtime
import zio.internal.PlatformLive

class AuthInfoSpec extends IzanamiSpec {

  "AuthInfo" must {
    "is admin" in {

      val authModule = new AuthInfoModule[String] with LoggerModule {
        override def authInfo: Option[AuthInfo] =
          Some(
            OauthUser("1",
                      "john.doe",
                      "john.doe@gmail.fr",
                      true,
                      AuthorizedPatterns(AuthorizedPattern("test", PatternRights.R)))
          )
        override def withAuthInfo(user: Option[AuthInfo]): String = ???
        override def logger: Logger                               = new ProdLogger
      }

      val r                                = Runtime(authModule, PlatformLive.Default)
      val res: Either[IzanamiErrors, Unit] = r.unsafeRun(AuthInfo.isAdmin().either)
      res must be(Right(()))
    }

    "is not admin" in {

      val authModule = new AuthInfoModule[String] with LoggerModule {
        override def authInfo: Option[AuthInfo] =
          Some(
            OauthUser("1",
                      "john.doe",
                      "john.doe@gmail.fr",
                      false,
                      AuthorizedPatterns(AuthorizedPattern("test", PatternRights.R)))
          )
        override def withAuthInfo(user: Option[AuthInfo]): String = ???
        override def logger: Logger                               = new ProdLogger
      }

      val r                                = Runtime(authModule, PlatformLive.Default)
      val res: Either[IzanamiErrors, Unit] = r.unsafeRun(AuthInfo.isAdmin().either)
      res must be(Left(NonEmptyList.of(Unauthorized(None))))
    }
  }
}
