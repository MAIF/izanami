package domains

import play.api.libs.json.{JsArray, JsError, JsPath, JsString, JsSuccess, Json, JsonValidationError}
import test.IzanamiSpec

class AuthorizedPatternsSpec extends IzanamiSpec {
  "PatternRight" must {
    "fromString" in {
      PatternRight.fromString("R") must be(Some(PatternRight.Read))
      PatternRight.fromString("C") must be(Some(PatternRight.Create))
      PatternRight.fromString("U") must be(Some(PatternRight.Update))
      PatternRight.fromString("D") must be(Some(PatternRight.Delete))
      PatternRight.fromString("T") must be(None)
    }

    "stringValue" in {
      PatternRight.stringValue(PatternRight.Read) must be("R")
      PatternRight.stringValue(PatternRight.Create) must be("C")
      PatternRight.stringValue(PatternRight.Update) must be("U")
      PatternRight.stringValue(PatternRight.Delete) must be("D")
    }

    "json reads" in {
      PatternRight.format.reads(JsString("R")) must be(JsSuccess(PatternRight.Read))
      PatternRight.format.reads(JsString("C")) must be(JsSuccess(PatternRight.Create))
      PatternRight.format.reads(JsString("U")) must be(JsSuccess(PatternRight.Update))
      PatternRight.format.reads(JsString("D")) must be(JsSuccess(PatternRight.Delete))
      PatternRight.format.reads(JsString("T")) must be(JsError(JsonValidationError("error.unknown.right", "T")))
      PatternRight.format.reads(Json.obj()) must be(JsError(JsonValidationError("error.unexpected.type", Json.obj())))
    }

    "json writes" in {
      PatternRight.format.writes(PatternRight.Read) must be(JsString("R"))
      PatternRight.format.writes(PatternRight.Create) must be(JsString("C"))
      PatternRight.format.writes(PatternRight.Update) must be(JsString("U"))
      PatternRight.format.writes(PatternRight.Delete) must be(JsString("D"))
    }
  }

  "PatternRights" must {
    "fromString" in {
      PatternRights.fromString("CRUD") must be(PatternRights.CRUD)
      PatternRights.fromString("C") must be(PatternRights.C)
      PatternRights.fromString("") must be(PatternRights.CRUD)
    }

    "stringValue" in {
      PatternRights.stringValue(PatternRights.CRUD) must be("CRUD")
      PatternRights.stringValue(PatternRights.C) must be("C")
    }

    "json reads" in {
      PatternRights.format.reads(Json.arr("R")) must be(JsSuccess(PatternRights.R))
      PatternRights.format.reads(Json.arr("C", "R", "U", "D")) must be(JsSuccess(PatternRights.CRUD))
      PatternRights.format.reads(Json.arr("T")) must be(
        JsError(Seq((JsPath(0), Seq(JsonValidationError("error.unknown.right", "T")))))
      )
    }

    "json writes" in {
      PatternRights.format.writes(PatternRights.CRUD) must be(Json.arr("C", "R", "U", "D"))
    }
  }

  "AuthorizedPattern" must {
    "fromString" in {
      AuthorizedPattern.fromString("*$$$R") must be(Some(AuthorizedPattern("*", PatternRights.R)))
      AuthorizedPattern.fromString("*") must be(Some(AuthorizedPattern("*", PatternRights.CRUD)))
    }

    "stringValue" in {
      AuthorizedPattern.stringValue(AuthorizedPattern("*", PatternRights.R)) must be("*$$$R")
      AuthorizedPattern.stringValue(AuthorizedPattern("*", PatternRights.CRUD)) must be("*$$$CRUD")
    }

    "json reads" in {
      AuthorizedPattern.format.reads(Json.obj("pattern" -> "*", "rights" -> Json.arr("R"))) must be(
        JsSuccess(AuthorizedPattern("*", PatternRights.R))
      )
      AuthorizedPattern.format.reads(JsString("pattern")) must be(
        JsSuccess(AuthorizedPattern("pattern", PatternRights.CRUD))
      )
      AuthorizedPattern.format.reads(JsString("pattern$$$R")) must be(
        JsSuccess(AuthorizedPattern("pattern", PatternRights.R))
      )
      AuthorizedPattern.format.reads(Json.arr("T")) must be(
        JsError(JsonValidationError("error.unexpected.type", Json.arr("T")))
      )
    }

    "json writes" in {
      AuthorizedPattern.format.writes(AuthorizedPattern("*", PatternRights.R)) must be(
        Json.obj("pattern" -> "*", "rights" -> Json.arr("R"))
      )
    }
  }

  "AuthorizedPatterns" must {
    "fromString" in {
      AuthorizedPatterns.fromString("*$$$R") must be(AuthorizedPatterns(AuthorizedPattern("*", PatternRights.R)))
      AuthorizedPatterns.fromString("*$$$R,test$$$CRUD,test2$$$UD") must be(
        AuthorizedPatterns(
          AuthorizedPattern("*", PatternRights.R),
          AuthorizedPattern("test", PatternRights.CRUD),
          AuthorizedPattern("test2", PatternRights.U ++ PatternRights.D)
        )
      )
      AuthorizedPatterns.fromString("abcdefg") must be(
        AuthorizedPatterns(AuthorizedPattern("abcdefg", PatternRights.CRUD))
      )
    }

    "stringValue" in {
      AuthorizedPatterns.stringValue(AuthorizedPatterns(AuthorizedPattern("*", PatternRights.R))) must be("*$$$R")
      AuthorizedPatterns.stringValue(
        AuthorizedPatterns(
          AuthorizedPattern("*", PatternRights.CRUD),
          AuthorizedPattern("test", PatternRights.C),
        )
      ) must be("*$$$CRUD,test$$$C")
    }

    "json reads" in {
      AuthorizedPatterns.format.reads(Json.arr(Json.obj("pattern" -> "*", "rights" -> Json.arr("R")))) must be(
        JsSuccess(AuthorizedPatterns(AuthorizedPattern("*", PatternRights.R)))
      )
      AuthorizedPatterns.format.reads(JsString("*$$$R,test$$$CRUD,test2$$$UD")) must be(
        JsSuccess(
          AuthorizedPatterns(
            AuthorizedPattern("*", PatternRights.R),
            AuthorizedPattern("test", PatternRights.CRUD),
            AuthorizedPattern("test2", PatternRights.U ++ PatternRights.D)
          )
        )
      )
    }

    "json writes" in {
      AuthorizedPatterns.format.writes(
        AuthorizedPatterns(
          AuthorizedPattern("*", PatternRights.R),
          AuthorizedPattern("test", PatternRights.CRUD),
          AuthorizedPattern("test2", PatternRights.U ++ PatternRights.D)
        )
      ) must be(
        Json.arr(
          Json.obj("pattern" -> "*", "rights"     -> Json.arr("R")),
          Json.obj("pattern" -> "test", "rights"  -> Json.arr("C", "R", "U", "D")),
          Json.obj("pattern" -> "test2", "rights" -> Json.arr("U", "D"))
        )
      )
    }

    "match pattern" in {

      val authorizedPatterns = AuthorizedPatterns(
        AuthorizedPattern("path1:*", PatternRights.R),
        AuthorizedPattern("path2", PatternRights.W),
        AuthorizedPattern("path3", PatternRights.CRUD),
        AuthorizedPattern("path4", PatternRights.U ++ PatternRights.D)
      )

      AuthorizedPatterns.isAllowed("path1:path2:path3", PatternRights.R, authorizedPatterns) must be(true)
      AuthorizedPatterns.isAllowed("path1:path2:path3", PatternRights.C, authorizedPatterns) must be(false)

      AuthorizedPatterns.isAllowed("path2", PatternRights.W, authorizedPatterns) must be(true)
      AuthorizedPatterns.isAllowed("path2", PatternRights.CRUD, authorizedPatterns) must be(false)
      AuthorizedPatterns.isAllowed("path2:path3", PatternRights.W, authorizedPatterns) must be(false)

      AuthorizedPatterns.isAllowed("path3", PatternRights.W, authorizedPatterns) must be(true)

      AuthorizedPatterns.isAllowed("path4", PatternRights.R, authorizedPatterns) must be(false)
    }
  }
}
