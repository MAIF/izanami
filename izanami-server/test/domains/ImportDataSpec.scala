package domains

import fs2.Pipe
import libs.logs.{Logger, LoggerModule, ProdLogger}
import play.api.libs.json.{JsObject, JsValue, Json}
import errors.IzanamiErrors
import test.IzanamiSpec
import zio.internal.PlatformLive
import zio.{Runtime, Task}

import scala.collection.mutable

case class Viking(id: String, name: String)

object Viking {
  implicit val format = Json.format[Viking]
}

class ImportDataSpec extends IzanamiSpec {

  private val runtime = Runtime(new LoggerModule {
    override def logger: Logger = new ProdLogger
  }, PlatformLive.Default)

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
          .importData[LoggerModule, String, Viking](ImportStrategy.Replace,
                                                    _.id,
                                                    key => Task(datas.get(key)).refineToOrDie[IzanamiErrors],
                                                    insert,
                                                    insert)
      )

      val dataStream: Stream[zio.Task, (String, JsObject)] = Stream(
        "" -> Json.obj("id" -> "1", "name" -> "Ragnar Lodbrok"),
        "" -> Json.obj("id" -> "2", "name" -> "Bjorn Ironside")
      )
//      val importPipe2: Pipe[Task, (String, JsValue), ImportResult] = { s =>
//        s.map(_._2)
//          .evalMap { _ =>
//            Task("value")
//          }
//          .map(_ => ImportResult(success = 1))
//          .fold(ImportResult()) { _ |+| _ }
//      }
      //println(
      //  runtime.unsafeRun(
      //    dataStream
      //      .through(importPipe2)
      //      .compile
      //      .drain
      //  )
      //)
      runtime.unsafeRun(
        dataStream
          .through(importPipe)
          .compile
          .toList
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
