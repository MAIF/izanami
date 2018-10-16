package libs
import cats.data.EitherT
import cats.effect.Effect
import play.api.mvc._

object http {

  implicit class ActionBuilderOps[+R[_], B](ab: ActionBuilder[R, B]) {
    import cats.implicits._
    import cats.effect.implicits._

    def asyncF[F[_]: Effect](cb: R[B] => F[Result]): Action[B] = ab.async { c =>
      cb(c).toIO.unsafeToFuture()
    }

    def asyncF[F[_]: Effect, A](bp: BodyParser[A])(cb: R[A] => F[Result]): Action[A] = ab.async[A](bp) { c =>
      cb(c).toIO.unsafeToFuture()
    }

    def asyncEitherT[F[_]: Effect](cb: R[B] => EitherT[F, Result, Result]): Action[B] = ab.async { c =>
      cb(c).value.map(_.merge).toIO.unsafeToFuture()
    }

    def asyncEitherT[F[_]: Effect, A](bp: BodyParser[A])(cb: R[A] => EitherT[F, Result, Result]): Action[A] =
      ab.async[A](bp) { c =>
        cb(c).value.map(_.merge).toIO.unsafeToFuture()
      }
  }

  implicit class RequestHeaderOps(rh: RequestHeader) {
    def protocol: String =
      rh.headers
        .get("X-Forwarded-Protocol")
        .orElse(rh.headers.get("X-Forwarded-Proto"))
        .map(_ == "https")
        .orElse(Some(rh.secure))
        .map {
          case true  => "https"
          case false => "http"
        }
        .getOrElse("http")
  }
}
