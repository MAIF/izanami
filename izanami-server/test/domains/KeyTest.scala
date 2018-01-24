package domains

import play.api.libs.json.{JsError, JsString, JsSuccess, Json}
import test.IzanamiSpec

/**
 * Created by adelegue on 17/07/2017.
 */
class KeyTest extends IzanamiSpec {

  "Key deserialization" must {

    "should be valid" in {
      val result = JsString("ragnar:lodbrok:730").validate[Key]
      result mustBe an[JsSuccess[_]]
      result.get must be(Key("ragnar:lodbrok:730"))
    }

    "should be invalid" in {
      val result = JsString("björn:ironside").validate[Key]
      result mustBe an[JsError]
    }

  }

  "Key serialization" must {

    "should be valid" in {
      Json.toJson(Key("ragnar:lodbrok:730")) must be(JsString("ragnar:lodbrok:730"))
    }
  }

  "Test patterns" must {
    "match" in {
      Key("test2:ab:scenario:B:8:displayed").matchPattern("test2:ab:scenario:*") must be(true)
    }
    "not match" in {
      Key("test1:ab:scenario:B:8:displayed").matchPattern("test2:ab:scenario:*") must be(false)
    }
  }
}
