package fr.maif.izanami.utils

object Helpers {
  def sequence[A, B](seq: Seq[Either[A, B]]): Either[A, Seq[B]] = seq.foldRight(Right(Nil): Either[A, List[B]]) {
    (e, acc) => for (xs <- acc; x <- e) yield x :: xs
  }
}
