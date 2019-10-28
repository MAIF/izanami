package domains

import play.api.libs.json.{JsError, JsString, JsSuccess, Json}
import store.{EmptyPattern, StringPattern}
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

    "match all is true" in {
      Key("test2:ab:scenario:B:8:displayed").matchAllPatterns("test2:ab:scenario:*", "test2:ab:*") must be(true)
    }

    "match all is false" in {
      Key("test1:ab:scenario:B:8:displayed").matchAllPatterns("test2:ab:scenario:*", "test1:ab:*") must be(false)
    }

    "match one str is true" in {
      Key("test1:ab:scenario:B:8:displayed").matchOneStrPatterns("test2:ab:scenario:*", "test1:ab:*") must be(true)
    }

    "match one is false" in {
      Key("test1:ab:scenario:B:8:displayed").matchOneStrPatterns("test2:ab:scenario:*", "test2:ab:*") must be(false)
    }

    "match one pattern is true" in {
      Key("test1:ab:scenario:B:8:displayed").matchOnePatterns(StringPattern("test2:ab:scenario:*"),
                                                              StringPattern("test1:ab:*")) must be(true)
    }

    "match one pattern with empty is false" in {
      Key("test1:ab:scenario:B:8:displayed")
        .matchOnePatterns(StringPattern("test2:ab:scenario:*"), EmptyPattern) must be(false)
    }

  }
}
