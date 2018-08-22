package libs

import cats.{Applicative, Functor, Monad}
import cats.data.EitherT
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by adelegue on 24/07/2017.
 */
package object functional {

  object syntax {

    implicit class Ops[In](in: In) {
      def |>[Out](f: In => Out): Out = f(in)

    }
  }

  trait EitherTSyntax[F[_]] {

    import cats.data.EitherT
    import cats.implicits._

    def liftFEither[E, A](f: F[Either[E, A]]): EitherT[F, E, A] = EitherT(f)

    def liftEither[E, A](f: Either[E, A])(implicit F: Applicative[F]): EitherT[F, E, A] = EitherT.fromEither[F](f)

    def mapLeft[E, E1, A](
        onError: E => E1
    )(f: F[Either[E, A]])(implicit F: Functor[F]): EitherT[F, E1, A] =
      EitherT(f.map(_.left.map(onError)))

    def liftF[E, A](f: F[A])(implicit F: Functor[F]): EitherT[F, E, A] =
      EitherT.liftF[F, E, A](f)

    def liftOption[E, A](ifNone: => E)(o: Option[A])(implicit F: Applicative[F]): EitherT[F, E, A] =
      EitherT.fromOption[F](o, ifNone)

    def liftFOption[E, A](ifNone: => E)(o: F[Option[A]])(implicit F: Functor[F]): EitherT[F, E, A] = {
      val futureEither: F[Either[E, A]] = o.map {
        case Some(value) => Right(value)
        case None        => Left(ifNone)
      }
      liftFEither(futureEither)
    }

    def liftJsResult[E, A](
        onError: Seq[(JsPath, Seq[JsonValidationError])] => E
    )(jsResult: JsResult[A])(implicit M: Monad[F]): EitherT[F, E, A] =
      jsResult match {
        case JsSuccess(value, _) => EitherT.right(M.pure(value))
        case JsError(errors) =>
          EitherT.left(M.pure(onError(errors)))
      }

    def pure[E, A](a: A)(implicit F: Applicative[F]): EitherT[F, E, A] = EitherT.pure[F, E](a)

    def liftBooleanTrue[E](
        ifFalse: => E
    )(fboolean: Boolean)(implicit F: Applicative[F]): EitherT[F, E, Boolean] =
      fboolean match {
        case true  => EitherT.right(F.pure(true))
        case false => EitherT.left(F.pure(ifFalse))
      }

    def liftBooleanFalse[E](
        ifTrue: => E
    )(fboolean: Boolean)(implicit F: Applicative[F]): EitherT[F, E, Boolean] =
      fboolean match {
        case true  => EitherT.left(F.pure(ifTrue))
        case false => EitherT.right(F.pure(false))
      }

    def liftFBooleanTrue[E](
        ifFalse: => E
    )(fboolean: F[Boolean])(implicit F: Applicative[F]): EitherT[F, E, Boolean] = {
      val futureEither: F[Either[E, Boolean]] = fboolean.map {
        case true  => Right(true)
        case false => Left(ifFalse)
      }
      liftFEither(futureEither)
    }

    def liftFBooleanFalse[E](
        ifTrue: => E
    )(fboolean: F[Boolean])(implicit F: Applicative[F]): EitherT[F, E, Boolean] = {
      val futureEither: F[Either[E, Boolean]] = fboolean.map {
        case true  => Left(ifTrue)
        case false => Right(false)
      }
      liftFEither(futureEither)
    }
  }

  object EitherTOps extends EitherTSyntax[Future]

}
