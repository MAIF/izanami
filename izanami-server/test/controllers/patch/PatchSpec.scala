package controllers.patch

import controllers.patch.Patch._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json._
import test.IzanamiSpec

class PatchSpec
  extends IzanamiSpec
    with ScalaFutures
    with IntegrationPatience {

  private val addJson = Json.obj("op" -> "add", "path" -> "/path/in/json", "value" -> Json.obj("test" -> "test"))
  private val add = Add(JsPath \ "path" \ "in" \ "json", Json.obj("test" -> "test"))

  private val removeJson = Json.obj("op" -> "remove", "path" -> "/path/in/json")
  private val remove = Remove(JsPath \ "path" \ "in" \ "json")

  private val replaceJson = Json.obj("op" -> "replace", "path" -> "/path/in/json", "value" -> Json.obj("test" -> "test"))
  private val replace = Replace(JsPath \ "path" \ "in" \ "json", Json.obj("test" -> "test"))

  private val copyJson = Json.obj("op" -> "copy", "from" -> "/path/in/json", "path" -> "/otherPath/in/json")
  private val copy = Copy(JsPath \ "path" \ "in" \ "json", JsPath \ "otherPath" \ "in" \ "json")

  private val moveJson = Json.obj("op" -> "move", "from" -> "/path/in/json", "path" -> "/otherPath/in/json")
  private val move = Move(JsPath \ "path" \ "in" \ "json", JsPath \ "otherPath" \ "in" \ "json")

  private val testJson = Json.obj("op" -> "test", "path" -> "/path/in/json", "value" -> Json.obj("test" -> "test"))
  private val test = Test(JsPath \ "path" \ "in" \ "json", Json.obj("test" -> "test"))


  "patch serialization and deserialization" must {

    "read add operation" in {
        val result: JsResult[Patch.Patch] = Patch.format.reads(addJson)
        result.isSuccess must be (true)
        result.get must be(add)
    }

    "write add operation" in {
      val result: JsValue = Patch.format.writes(add)
      result must be(addJson)
    }

    "read remove operation" in {
      val result: JsResult[Patch.Patch] = Patch.format.reads(removeJson)
      result.isSuccess must be (true)
      result.get must be(remove)
    }

    "write remove operation" in {
      val result: JsValue = Patch.format.writes(remove)
      result must be(removeJson)
    }


    "read replace operation" in {
      val result: JsResult[Patch.Patch] = Patch.format.reads(replaceJson)
      result.isSuccess must be (true)
      result.get must be(replace)
    }

    "write replace operation" in {
      val result: JsValue = Patch.format.writes(replace)
      result must be(replaceJson)
    }

    "read copy operation" in {
      val result: JsResult[Patch.Patch] = Patch.format.reads(copyJson)
      result.isSuccess must be (true)
      result.get must be(copy)
    }

    "write copy operation" in {
      val result: JsValue = Patch.format.writes(copy)
      result must be(copyJson)
    }

    "read move operation" in {
      val result: JsResult[Patch.Patch] = Patch.format.reads(moveJson)
      result.isSuccess must be (true)
      result.get must be(move)
    }

    "write move operation" in {
      val result: JsValue = Patch.format.writes(move)
      result must be(moveJson)
    }

    "read test operation" in {
      val result: JsResult[Patch.Patch] = Patch.format.reads(testJson)
      result.isSuccess must be (true)
      result.get must be(test)
    }

    "write test operation" in {
      val result: JsValue = Patch.format.writes(test)
      result must be(testJson)
    }
  }

  "apply patch" must {

    "add" in {
      val json = Json.obj("name" -> "Ragnar Lodbrock")

      val result: JsResult[JsObject] = Patch.patch(Seq(add), json)
      result.isSuccess must be (true)

      result.get must be(Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "test"
            )
          )
        )
      ))
    }

    "remove" in {
      val json = Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "test"
            )
          )
        )
      )

      val result: JsResult[JsObject] = Patch.patch(Seq(remove), json)
      result.isSuccess must be (true)

      result.get must be(Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj()
        )
      ))
    }


    "replace" in {
      val json = Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "testOld",
              "other" -> "otherField"
            )
          )
        )
      )

      val result: JsResult[JsObject] = Patch.patch(Seq(replace), json)
      result.isSuccess must be (true)

      result.get must be(Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "test"
            )
          )
        )
      ))
    }

    "replace in array" in {
      val json = Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.arr(Json.obj(
            "json" -> Json.obj(
              "test" -> "testOld",
              "other" -> "otherField"
            )
          )
        ))
      )

      val replace = Replace((JsPath \ "path" \ "in" \ "json")(0) \ "test", JsString("test"))
      val result: JsResult[JsObject] = Patch.patch(Seq(replace), json)
      println(result)
      result.isSuccess must be (true)

      result.get must be(Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.arr(Json.obj(
            "json" -> Json.obj(
              "test" -> "test",
              "other" -> "otherField"
            )
          )
          ))
      ))
    }

    "copy" in {
      val json = Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "testOld",
              "other" -> "otherField"
            )
          )
        )
      )

      val result: JsResult[JsObject] = Patch.patch(Seq(copy), json)
      result.isSuccess must be (true)

      result.get must be(Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "testOld",
              "other" -> "otherField"
            )
          )
        ),
        "otherPath" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "testOld",
              "other" -> "otherField"
            )
          )
        )
      ))
    }

    "move" in {
      val json = Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "testOld",
              "other" -> "otherField"
            )
          )
        )
      )

      val result: JsResult[JsObject] = Patch.patch(Seq(move), json)
      result.isSuccess must be (true)

      result.get must be(Json.obj(
        "name" -> "Ragnar Lodbrock",
        "otherPath" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "testOld",
              "other" -> "otherField"
            )
          )
        ),
        "path" -> Json.obj(
          "in" -> Json.obj()
        )
      ))
    }

    "test" in {
      val json = Json.obj(
        "name" -> "Ragnar Lodbrock",
        "path" -> Json.obj(
          "in" -> Json.obj(
            "json" -> Json.obj(
              "test" -> "test"
            )
          )
        )
      )

      val result: JsResult[JsObject] = Patch.patch(Seq(test), json)
      result.isSuccess must be (true)

      val result1: JsResult[JsObject] = Patch.patch(Seq(Test(JsPath \ "path" \ "in" \ "json", Json.obj("test" -> "test1"))), json)
      result1.isSuccess must be (false)
    }
  }
}
