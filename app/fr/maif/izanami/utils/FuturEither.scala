package fr.maif.izanami.utils

import fr.maif.izanami.errors.IzanamiError
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

case class FutureEither[+A](value: Future[Either[IzanamiError, A]]) {
  def flatMap[B](
      transformer: A => FutureEither[B]
  )(implicit ec: ExecutionContext): FutureEither[B] = {
    new FutureEither(
      value
        .map {
          case Left(value)  => FutureEither[B](Future.successful(Left(value)))
          case Right(value) => transformer(value)
        }
        .flatMap(io => io.value)
    )
  }

  def flatMapF[B](
      transformer: A => Future[B]
  )(implicit ec: ExecutionContext): FutureEither[B] = {
    flatMap(a => FutureEither.success(transformer(a)))
  }

  def map[B](
      transformer: A => B
  )(implicit ec: ExecutionContext): FutureEither[B] = {
    new FutureEither[B](value.map {
      case Left(value)  => Left(value)
      case Right(value) => Right(transformer(value))
    })
  }

  def toResult(
      mapper: A => play.api.mvc.Result
  )(implicit ec: ExecutionContext): Future[Result] = {
    value.map {
      case Left(error)  => error.toHttpResponse
      case Right(value) => mapper(value)
    }
  }

  def toFutureResult(
                mapper: A => Future[play.api.mvc.Result]
              )(implicit ec: ExecutionContext): Future[Result] = {
    value.flatMap {
      case Left(error) => Future.successful(error.toHttpResponse)
      case Right(value) => mapper(value)
    }
  }

  def foreach(mapper: A => Unit)(implicit ec: ExecutionContext): Unit = {
    value.foreach {
      case Left(_)      => ()
      case Right(value) => mapper(value)
    }
  }

  def fold[R](errMapper: IzanamiError => R, valueMapper: A => R)(implicit
      ec: ExecutionContext
  ): Future[R] = {
    value.map({
      case Left(err) => errMapper(err)
      case Right(v)  => valueMapper(v)
    })
  }
}

object FutureEither {
  def from[A](value: Either[IzanamiError, A])(implicit
      ec: ExecutionContext
  ): FutureEither[A] = FutureEither(Future.successful(value))
  def success[A](value: A): FutureEither[A] = FutureEither(
    Future.successful(Right(value))
  )
  def success[A](value: Future[A])(implicit
      ec: ExecutionContext
  ): FutureEither[A] = FutureEither(value.map(a => Right[IzanamiError, A](a)))
  def failure[A](error: IzanamiError): FutureEither[A] = FutureEither(
    Future.successful(Left(error))
  )
  def sequence[A](
      s: IterableOnce[FutureEither[A]]
  )(implicit ec: ExecutionContext): FutureEither[IterableOnce[A]] = {
    s.iterator.toList match {
      case head :: tail =>
        head.flatMap(a =>
          sequence(tail).map(as => as.iterator.toList.prepended(a))
        )
      case Nil => FutureEither.success(Seq())
    }
  }
}
