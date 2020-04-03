package libs
import zio._
import play.api.mvc._
import domains.auth.AuthInfo
import cats.implicits._
import controllers.actions.{AuthContext, SecuredAuthContext}

import scala.concurrent.Future

package object http {

  type HttpContext[A] = ZLayer[ZEnv, Throwable, A]

  implicit class ActionBuilderOps[+R[_], B](ab: ActionBuilder[R, B]) {

    case class AsyncTaskBuilder[Ctx <: AuthInfo](dummy: Boolean = false) {

      def apply(cb: R[B] => RIO[Ctx, Result])(implicit r: HttpContext[Ctx]): Action[B] =
        ab.async { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).provideLayer(r)
          val future: Future[Result]              = Runtime.default.unsafeRunToFuture(value)
          future
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: R[A] => RIO[Ctx, Result])(implicit r: HttpContext[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).provideLayer(r)
          val future: Future[Result]              = Runtime.default.unsafeRunToFuture(value)
          future
        }
    }

    case class AsyncZioBuilder[Ctx <: AuthInfo](dummy: Boolean = false) {

      def apply(cb: R[B] => ZIO[Ctx, Result, Result])(implicit r: HttpContext[Ctx]): Action[B] =
        ab.async { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).either.map(_.merge).provideLayer(r)
          val future: Future[Result]              = Runtime.default.unsafeRunToFuture(value)
          future
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: R[A] => ZIO[Ctx, Result, Result])(implicit r: HttpContext[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).either.map(_.merge).provideLayer(r)
          val future: Future[Result]              = Runtime.default.unsafeRunToFuture(value)
          future
        }
    }

    def asyncTask[Ctx <: AuthInfo] = AsyncTaskBuilder[Ctx]()

    def asyncZio[Ctx <: AuthInfo] = AsyncZioBuilder[Ctx]()
  }

  implicit class SecuredActionBuilderOps[B](ab: ActionBuilder[SecuredAuthContext, B]) {

    case class AsyncTaskBuilder[Ctx <: AuthInfo](dummy: Boolean = false) {

      def apply(cb: SecuredAuthContext[B] => RIO[Ctx, Result])(implicit r: HttpContext[Ctx]): Action[B] =
        ab.async { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).provideLayer(r.update[Option[AuthInfo.Service]] { _ =>
            c.auth
          })
          val future: Future[Result] = Runtime.default.unsafeRunToFuture(value)
          future
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: SecuredAuthContext[A] => RIO[Ctx, Result])(implicit r: HttpContext[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).provideLayer(r.update[Option[AuthInfo.Service]] { _ =>
            c.auth
          })
          val future: Future[Result] = Runtime.default.unsafeRunToFuture(value)
          future
        }
    }

    case class AsyncZioBuilder[Ctx <: AuthInfo](dummy: Boolean = false) {

      def apply(
          cb: SecuredAuthContext[B] => ZIO[Ctx, Result, Result]
      )(implicit r: HttpContext[Ctx]): Action[B] =
        ab.async { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).either
            .map(_.merge)
            .provideLayer(r.update[Option[AuthInfo.Service]] { _ =>
              c.auth
            })
          val future: Future[Result] = Runtime.default.unsafeRunToFuture(value)
          future
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: SecuredAuthContext[A] => ZIO[Ctx, Result, Result])(implicit r: HttpContext[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).either
            .map(_.merge)
            .provideLayer(r.update[Option[AuthInfo.Service]] { _ =>
              c.auth
            })
          val future: Future[Result] = Runtime.default.unsafeRunToFuture(value)
          future
        }
    }

    def asyncTask[Ctx <: AuthInfo] = AsyncTaskBuilder[Ctx]()

    def asyncZio[Ctx <: AuthInfo] = AsyncZioBuilder[Ctx]()
  }

  implicit class AuthContextBuilderOps[B](ab: ActionBuilder[AuthContext, B]) {

    case class AsyncTaskBuilder[Ctx <: AuthInfo](dummy: Boolean = false) {

      def apply(cb: AuthContext[B] => RIO[Ctx, Result])(implicit r: HttpContext[Ctx]): Action[B] =
        ab.async { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).provideLayer(r.update[Option[AuthInfo.Service]] { _ =>
            c.auth
          })
          val future: Future[Result] = Runtime.default.unsafeRunToFuture(value)
          future
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: AuthContext[A] => RIO[Ctx, Result])(implicit r: HttpContext[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).provideLayer(r.update[Option[AuthInfo.Service]] { _ =>
            c.auth
          })
          val future: Future[Result] = Runtime.default.unsafeRunToFuture(value)
          future
        }
    }

    case class AsyncZioBuilder[Ctx <: AuthInfo](dummy: Boolean = false) {

      def apply(cb: AuthContext[B] => ZIO[Ctx, Result, Result])(implicit r: HttpContext[Ctx]): Action[B] =
        ab.async { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).either
            .map(_.merge)
            .provideLayer(r.update[Option[AuthInfo.Service]] { _ =>
              c.auth
            })
          val future: Future[Result] = Runtime.default.unsafeRunToFuture(value)
          future
        }

      def apply[A](
          bp: BodyParser[A]
      )(cb: AuthContext[A] => ZIO[Ctx, Result, Result])(implicit r: HttpContext[Ctx]): Action[A] =
        ab.async[A](bp) { c =>
          val value: ZIO[ZEnv, Throwable, Result] = cb(c).either
            .map(_.merge)
            .provideLayer(r.update[Option[AuthInfo.Service]] { _ =>
              c.auth
            })
          val future: Future[Result] = Runtime.default.unsafeRunToFuture(value)
          future
        }
    }

    def asyncTask[Ctx <: AuthInfo] = AsyncTaskBuilder[Ctx]()

    def asyncZio[Ctx <: AuthInfo] = AsyncZioBuilder[Ctx]()
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
