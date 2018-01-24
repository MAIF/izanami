package izanami.scaladsl

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import play.api.libs.json.{JsString, Json}

class ConfigSpec extends WordSpec with MustMatchers with OptionValues {

  "config" should {
    "deserialise config string" in {

      val result = Config.format.reads(Json.parse("""
            |{"id": "a:key", "value": "1 d" }
          """.stripMargin))

      result.isSuccess must be(true)
      result.get.value must be(JsString("1 d"))
    }

    "deserialise config a string string" in {

      val result = Config.format.reads(Json.parse("""
          |{"id": "a:key", "value": "\"1 d\"" }
        """.stripMargin))

      result.isSuccess must be(true)
      result.get.value must be(JsString("1 d"))
    }

    "deserialise config json " in {

      val result = Config.format.reads(Json.parse("""
            |{"id": "a:key", "value": { "url": "https://www.youtube.com" } }
          """.stripMargin))

      result.isSuccess must be(true)
      result.get.value must be(Json.obj("url" -> "https://www.youtube.com"))

    }

    "deserialise config json string" in {

      val result = Config.format.reads(Json.parse("""
            |{"id": "a:key", "value": "{ \"url\": \"https://www.youtube.com\" }" }
          """.stripMargin))

      result.isSuccess must be(true)
      result.get.value must be(Json.obj("url" -> "https://www.youtube.com"))

    }
  }

}
