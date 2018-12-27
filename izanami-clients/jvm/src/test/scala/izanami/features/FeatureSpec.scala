package izanami.features
import izanami._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json._

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
        JsSuccess(ScriptFeature("id", true, None, Script("javascript", "script")), __ \ "parameters" \ "script")
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
        JsSuccess(ScriptFeature("id", true, None, Script("javascript", "script")), __ \ "parameters" \ "script")
      )
    }
  }

}
