package libs
import cats.data.NonEmptyList
import play.api.libs.json._
import play.api.libs.json.Reads._

object json {

  private def nelReads[A](implicit reads: Reads[A]): Reads[NonEmptyList[A]] = __.read[List[A]].flatMap {
    case h :: t => Reads.pure(NonEmptyList(h, t))
    case _ =>
      Reads[NonEmptyList[A]] { _ =>
        JsError("nonemptylist.expected")
      }
  }
  private def nelWrites[A](implicit writes: Writes[List[A]]): Writes[NonEmptyList[A]] =
    Writes[NonEmptyList[A]] { nel =>
      writes.writes(nel.toList)
    }

  implicit def nelFormat[A](implicit format: Format[A]): Format[NonEmptyList[A]] =
    Format(nelReads[A], nelWrites[A])

}
