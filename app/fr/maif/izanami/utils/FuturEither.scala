package fr.maif.izanami.utils

import fr.maif.izanami.errors.IzanamiError

import scala.concurrent.{ExecutionContext, Future}

case class FutureEither[+A](value: Future[Either[IzanamiError, A]]) {
  def flatMap[B](transformer: A => FutureEither[B])(implicit ec: ExecutionContext): FutureEither[B] = {
    new FutureEither(
      value
        .map {
          case Left(value)  => FutureEither[B](Future.successful(Left(value)))
          case Right(value) => transformer(value)
        }
        .flatMap(io => io.value)
    )
  }

  def map[B](transformer: A => B)(implicit ec: ExecutionContext): FutureEither[B] = {
    new FutureEither[B](value.map {
      case Left(value) => Left(value)
      case Right(value) => Right(transformer(value))
    })
  }
}

object FutureEither {
  def success[A](value: A): FutureEither[A]            = FutureEither(Future.successful(Right(value)))
  def failure[A](error: IzanamiError): FutureEither[A] = FutureEither(Future.successful(Left(error)))
}
