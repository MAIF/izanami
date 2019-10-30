package izanami.features
import izanami._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json._
import java.time.LocalTime

import izanami.scaladsl.Features

class FeatureSpec
    extends IzanamiSpec
    with BeforeAndAfterAll
    with MockitoSugar
    with FeatureServer
    with FeatureMockServer {

  "serialization" should {

    "Feature Script old version" in {
      import izanami.Feature
      import izanami.Feature._
      val json = Json.parse("""
                              |{
                              |   "id": "id",
                              |   "enabled": true,
                              |   "activationStrategy": "SCRIPT",
                              |   "parameters": { "script": "script"}
                              |}
                            """.stripMargin)

      val jsResult = json.validate[Feature]
      jsResult must be(
        JsSuccess(ScriptFeature("id", true, None, Script("javascript", "script")))
      )
    }

    "Feature Script" in {
      import izanami.Feature
      import izanami.Feature._
      val json = Json.parse("""
                              |{
                              |   "id": "id",
                              |   "enabled": true,
                              |   "activationStrategy": "SCRIPT",
                              |   "parameters": { "script": {"type": "javascript", "script": "script"} }
                              |}
                            """.stripMargin)

      val jsResult = json.validate[Feature]
      jsResult must be(
        JsSuccess(ScriptFeature("id", true, None, Script("javascript", "script")))
      )
    }

    "Default feature deserialization" in {
      import izanami.Feature
      import izanami.Feature._

      val json = Json.parse("""{"id": "test", "enabled": true}""")

      json.validate[Feature] must be(JsSuccess(DefaultFeature("test", true)))
    }

    "Feature hour range serialization" in {

      import izanami.Feature
      import izanami.Feature._

      val json = Json.parse("""
                              |{
                              |   "id": "id",
                              |   "enabled": true,
                              |   "activationStrategy": "HOUR_RANGE",
                              |   "parameters": { "startAt": "05:25", "endAt": "16:30" }
                              |}
                            """.stripMargin)

      val jsResult = json.validate[Feature]
      jsResult must be(
        JsSuccess(HourRangeFeature("id", true, None, LocalTime.of(5, 25), LocalTime.of(16, 30)))
      )
    }

    "Feature hour range deserialization" in {

      import izanami.Feature._

      val json = Json.parse("""
                              |{
                              |   "id": "id",
                              |   "enabled": true,
                              |   "activationStrategy": "HOUR_RANGE",
                              |   "parameters": { "startAt": "05:25", "endAt": "16:30" }
                              |}
                            """.stripMargin)

      val written = Json.toJson(HourRangeFeature("id", true, None, LocalTime.of(5, 25), LocalTime.of(16, 30)))
      written must be(json)
    }
  }

  "Features" should {

    "Extract feature to create with empty list found" in {
      val toCreate =
        Features(ClientConfig(""), Seq(DefaultFeature("id", true))).featureToCreate(Seq.empty, Seq("*"))
      toCreate must be(Seq(DefaultFeature("id", true)))
    }

    "Extract feature to create" in {
      val toCreate = Features(ClientConfig(""), Seq(DefaultFeature("id", true)))
        .featureToCreate(Seq(DefaultFeature("id2", true)), Seq("*"))
      toCreate must be(Seq(DefaultFeature("id", true)))
    }
  }

}
