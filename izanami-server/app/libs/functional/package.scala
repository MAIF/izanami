package libs

import akka.http.scaladsl.util.FastFuture
import cats.data.EitherT
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by adelegue on 24/07/2017.
 */
package object functional {

  object Implicits {

    implicit class Ops[In](in: In) {
      def |>[Out](f: In => Out): Out = f(in)

    }
    implicit def mergePlayResult[L <: Result, R <: Result](value: EitherT[Future, L, R])(
        implicit ec: ExecutionContext
    ): Future[Result] = value.value.map(_.merge)
    implicit def mergeResult[A](value: EitherT[Future, A, A])(implicit ec: ExecutionContext): Future[A] =
      value.value.map(_.merge)
  }

  object EitherTOps {

    import cats.data.EitherT
    import cats.implicits._

    def liftFEither[E, A](f: Future[Either[E, A]])(implicit ec: ExecutionContext): EitherT[Future, E, A] = EitherT(f)

    def mapLeft[E, E1, A](
        onError: E => E1
    )(f: Future[Either[E, A]])(implicit ec: ExecutionContext): EitherT[Future, E1, A] =
      EitherT(f.map(_.left.map(onError)))

    def liftFuture[E, A](f: Future[A])(implicit ec: ExecutionContext): EitherT[Future, E, A] =
      EitherT.liftF[Future, E, A](f)

    def liftOption[E, A](ifNone: => E)(o: Option[A])(implicit ec: ExecutionContext): EitherT[Future, E, A] =
      EitherT.fromOption[Future](o, ifNone)

    def liftFOption[E, A](ifNone: => E)(o: Future[Option[A]])(implicit ec: ExecutionContext): EitherT[Future, E, A] = {
      val futureEither: Future[Either[E, A]] = o.map {
        case Some(value) => Right(value)
        case None        => Left(ifNone)
      }
      liftFEither(futureEither)
    }

    def liftJsResult[E, A](
        onError: Seq[(JsPath, Seq[JsonValidationError])] => E
    )(jsResult: JsResult[A])(implicit ec: ExecutionContext): EitherT[Future, E, A] =
      jsResult match {
        case JsSuccess(value, _) => EitherT.right(FastFuture.successful(value))
        case JsError(errors) =>
          EitherT.left(FastFuture.successful(onError(errors)))
      }

    def lift[E, A](a: A)(implicit ec: ExecutionContext): EitherT[Future, E, A] =
      EitherT.pure(a)

    def liftBooleanTrue[E](
        ifFalse: => E
    )(fboolean: Boolean)(implicit ec: ExecutionContext): EitherT[Future, E, Boolean] =
      fboolean match {
        case true  => EitherT.right(FastFuture.successful(true))
        case false => EitherT.left(FastFuture.successful(ifFalse))
      }

    def liftBooleanFalse[E](
        ifTrue: => E
    )(fboolean: Boolean)(implicit ec: ExecutionContext): EitherT[Future, E, Boolean] =
      fboolean match {
        case true  => EitherT.left(FastFuture.successful(ifTrue))
        case false => EitherT.right(FastFuture.successful(false))
      }

    def liftFBooleanTrue[E](
        ifFalse: => E
    )(fboolean: Future[Boolean])(implicit ec: ExecutionContext): EitherT[Future, E, Boolean] = {
      val futureEither: Future[Either[E, Boolean]] = fboolean.map {
        case true  => Right(true)
        case false => Left(ifFalse)
      }
      liftFEither(futureEither)
    }

    def liftFBooleanFalse[E](
        ifTrue: => E
    )(fboolean: Future[Boolean])(implicit ec: ExecutionContext): EitherT[Future, E, Boolean] = {
      val futureEither: Future[Either[E, Boolean]] = fboolean.map {
        case true  => Left(ifTrue)
        case false => Right(false)
      }
      liftFEither(futureEither)
    }
  }

}
