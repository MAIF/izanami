package fr.maif.izanami.utils

import pureconfig.ConfigFieldMapping
import pureconfig.generic.derivation.*

import scala.concurrent.{ExecutionContext, Future}

object Helpers {
  def sequence[A, B](seq: Seq[Either[A, B]]): Either[A, Seq[B]] =
    seq.foldRight(Right(Nil): Either[A, List[B]]) { (e, acc) =>
      for (xs <- acc; x <- e) yield x :: xs
    }

  def sequence[A, B, C](
      seq: Seq[Either[A, B]],
      init: => C,
      acc: (C, A) => C
  ): Either[C, Seq[B]] =
    seq.foldRight(Right(Nil): Either[C, List[B]]) {
      case (Left(err), Left(errAcc)) => Left(acc(errAcc, err))
      case (Left(err), Right(_))     => Left(acc(init, err))
      case (Right(v), Right(vs))     => Right(v :: vs)
      case (Right(_), Left(errAcc))  => Left(errAcc)
    }

  def chainFutures[E, R](
      futures: Seq[Future[Either[E, R]]]
  )(implicit ec: ExecutionContext): Future[Either[Seq[E], Seq[R]]] = {
    def act(
        fs: Seq[Future[Either[E, R]]],
        res: Either[Seq[E], Seq[R]]
    ): Future[Either[Seq[E], Seq[R]]] = {
      fs.headOption
        .map(f => {
          f.map(e =>
            (res, e) match {
              case (Left(errs), Left(err)) => Left(errs.appended(err))
              case (_, Left(err))          => Left(Seq(err))
              case (Left(errs), _)         => Left(errs)
              case (Right(rs), Right(r))   => Right(rs.appended(r))
            }
          ).flatMap(e => act(fs.tail, e))
        })
        .getOrElse(Future.successful(res))
    }

    act(futures, Right(Seq()))
  }
}

object LowerCaseEnumReader
    extends EnumConfigReaderDerivation(
      ConfigFieldMapping(str => {
        str.toLowerCase
      })
    )
