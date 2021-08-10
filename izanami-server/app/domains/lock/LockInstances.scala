package domains.lock

import domains.Key
import domains.lock.Lock.LockKey

object LockInstances {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  val reads: Reads[IzanamiLock] = (
    (__ \ "id").read[Key] and
    (__ \ "locked").read[Boolean]
  )(IzanamiLock.apply _)

  val writes: Writes[IzanamiLock] = Writes {
    case l: IzanamiLock => {
      (((__ \ "id").write[LockKey] and
      (__ \ "locked").write[Boolean])(unlift(IzanamiLock.unapply))).writes(l)
    }
  }

  implicit val format: Format[IzanamiLock] = Format(reads, writes)
}
