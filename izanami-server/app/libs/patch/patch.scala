package libs.patch

import diffson._
import jsonpatch.{Operation, _}
import diffson.playJson._
import diffson.playJson.DiffsonProtocol._
import play.api.libs.json._

object Patch {

  def patch[T](patchs: JsValue, value: T)(implicit reads: Reads[T], writes: Writes[T]): JsResult[T] =
    for {
      ops     <- patchs.validate[List[Operation[JsValue]]]
      patch   = JsonPatch(ops)
      toPatch = writes.writes(value)
      patched <- patch(toPatch)
      r       <- reads.reads(patched)
    } yield r

}
