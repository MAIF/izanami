package libs.patch

import play.api.libs.json._
import gnieh.diffson.playJson._

object Patch {

  def patch[T](patchs: JsValue, value: T)(implicit reads: Reads[T], writes: Writes[T]): JsResult[T] = {
    val patch            = JsonPatch(patchs)
    val toPatch: JsValue = writes.writes(value)
    val patched: JsValue = patch(toPatch)
    reads.reads(patched)
  }

}
