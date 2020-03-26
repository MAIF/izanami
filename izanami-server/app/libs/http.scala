package libs
import zio._
import play.api.mvc._
import domains.configuration.AuthInfoModule
import cats.implicits._
import controllers.actions.{AuthContext, SecuredAuthContext}

object http {

  implicit class ActionBuilderOps[+R[_], B](ab: ActionBuilder[R, B]) {

    case class AsyncTaskBuilder[Ctx <: AuthInfoModule](dummy: Boolean = false) {

      def apply(cb: R[B] => RIO[Ctx, Result])(implicit r: Runtime[Ctx]): Action[B] =
        ab.async { c =>
          r.unsafeRunToFuture(cb(c))
        }

      def apply[A](bp: BodyParser[A])(cb: R[A] => RIO[Ctx, Result])(implicit r: Runtime[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          r.unsafeRunToFuture(cb(c))
        }
    }

    case class AsyncZioBuilder[Ctx <: AuthInfoModule](dummy: Boolean = false) {

      def apply(cb: R[B] => ZIO[Ctx, Result, Result])(implicit r: Runtime[Ctx]): Action[B] =
        ab.async { c =>
          r.unsafeRunToFuture(cb(c).either.map(_.merge))
        }

      def apply[A](bp: BodyParser[A])(cb: R[A] => ZIO[Ctx, Result, Result])(implicit r: Runtime[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          r.unsafeRunToFuture(cb(c).either.map(_.merge))
        }
    }

    def asyncTask[Ctx <: AuthInfoModule] = AsyncTaskBuilder[Ctx]()

    def asyncZio[Ctx <: AuthInfoModule] = AsyncZioBuilder[Ctx]()
  }

  implicit class SecuredActionBuilderOps[B](ab: ActionBuilder[SecuredAuthContext, B]) {

    case class AsyncTaskBuilder[Ctx <: AuthInfoModule](dummy: Boolean = false) {

      def apply(cb: SecuredAuthContext[B] => RIO[Ctx, Result])(implicit r: Runtime[Ctx]): Action[B] =
        ab.async { c =>
          r // FIXME .map { _.withAuthInfo(c.auth) }
            .unsafeRunToFuture(cb(c))
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: SecuredAuthContext[A] => RIO[Ctx, Result])(implicit r: Runtime[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          r // FIXME .map { _.withAuthInfo(c.auth) }
            .unsafeRunToFuture(cb(c))
        }
    }

    case class AsyncZioBuilder[Ctx <: AuthInfoModule](dummy: Boolean = false) {

      def apply(cb: SecuredAuthContext[B] => ZIO[Ctx, Result, Result])(implicit r: Runtime[Ctx]): Action[B] =
        ab.async { c =>
          r // FIXME .map { _.withAuthInfo(c.auth) }
            .unsafeRunToFuture(cb(c).either.map(_.merge))
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: SecuredAuthContext[A] => ZIO[Ctx, Result, Result])(implicit r: Runtime[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          r // FIXME .map { _.withAuthInfo(c.auth) }
            .unsafeRunToFuture(cb(c).either.map(_.merge))
        }
    }

    def asyncTask[Ctx <: AuthInfoModule] = AsyncTaskBuilder[Ctx]()

    def asyncZio[Ctx <: AuthInfoModule] = AsyncZioBuilder[Ctx]()
  }

  implicit class AuthContextBuilderOps[B](ab: ActionBuilder[AuthContext, B]) {

    case class AsyncTaskBuilder[Ctx <: AuthInfoModule](dummy: Boolean = false) {

      def apply(cb: AuthContext[B] => RIO[Ctx, Result])(implicit r: Runtime[Ctx]): Action[B] =
        ab.async { c =>
          r //FIXME .map { _.withAuthInfo(c.auth) }
            .unsafeRunToFuture(cb(c))
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: AuthContext[A] => RIO[Ctx, Result])(implicit r: Runtime[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          r //FIXME .map { _.withAuthInfo(c.auth) }
            .unsafeRunToFuture(cb(c))
        }
    }

    case class AsyncZioBuilder[Ctx <: AuthInfoModule](dummy: Boolean = false) {

      def apply(cb: AuthContext[B] => ZIO[Ctx, Result, Result])(implicit r: Runtime[Ctx]): Action[B] =
        ab.async { c =>
          r // FIXME .map { _.withAuthInfo(c.auth) }
            .unsafeRunToFuture(cb(c).either.map(_.merge))
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: AuthContext[A] => ZIO[Ctx, Result, Result])(implicit r: Runtime[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          r // FIXME .map { _.withAuthInfo(c.auth) }
            .unsafeRunToFuture(cb(c).either.map(_.merge))
        }
    }

    def asyncTask[Ctx <: AuthInfoModule] = AsyncTaskBuilder[Ctx]()

    def asyncZio[Ctx <: AuthInfoModule] = AsyncZioBuilder[Ctx]()
  }

  implicit class RequestHeaderOps(rh: RequestHeader) {
    def protocol: String =
      rh.headers
        .get("X-Forwarded-Protocol")
        .orElse(rh.headers.get("X-Forwarded-Proto"))
        .map(_ === "https")
        .orElse(Some(rh.secure))
        .map {
          case true  => "https"
          case false => "http"
        }
        .getOrElse("http")
  }
}
