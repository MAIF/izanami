package domains

import fs2.Pipe
import play.api.libs.json.{JsValue, Json}
import store.Result.IzanamiErrors
import test.IzanamiSpec
import zio.{DefaultRuntime, RIO, Runtime, Task, ZIO}

import scala.collection.mutable

case class Viking(id: String, name: String)

object Viking {
  implicit val format = Json.format[Viking]
}

class ImportDataSpec extends IzanamiSpec {

  private val runtime = new DefaultRuntime {}

  "Import data" must {
    "replace current data" in {

      import fs2._
      import Viking._
      import zio.interop.catz._

      val datas: mutable.Map[String, Viking] = mutable.Map(
        "1" -> Viking("1", "Ragnar")
      )

      val insert: (String, Viking) => zio.IO[IzanamiErrors, Viking] = (key, v) =>
        Task {
          datas += (key -> v)
          v
        }.refineToOrDie[IzanamiErrors]

      val importPipe: Pipe[Task, (String, JsValue), ImportResult] = runtime.unsafeRun(
        ImportData
          .importData[Any, String, Viking](ImportStrategy.Replace, _.id, key => Task(datas.get(key)), insert, insert)
      )

      val list: List[ImportResult] = runtime.unsafeRun(
        Stream(
          "" -> Json.obj("id" -> "1", "name" -> "Ragnar Lodbrok"),
          "" -> Json.obj("id" -> "2", "name" -> "Bjorn Ironside"),
        ).through(importPipe).compile.toList
      )
      datas must be(
        Map(
          "1" -> Viking("1", "Ragnar Lodbrok"),
          "2" -> Viking("2", "Bjorn Ironside")
        )
      )
    }
  }
}
