package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec._
import org.scalatest.Matchers.defined
import play.api.libs.json.JsObject

import java.time.LocalDateTime

class FeatureCallAPISpec extends BaseAPISpec {
  "stale feature tracking" should {
    "not report too old never called feature as stale if stale tracking is disabled" in {
      val situation = TestSituationBuilder().withTenants(TestTenant("test")
        .withProjects(TestProject("proj")
          .withFeatureNames("feat")
        )
      ).withCustomConfiguration(Map("app.experimental.stale-tracking.enabled" -> "false"))
        .loggedInWithAdminRights()
        .build()

      val featureId = situation.findFeatureId("test", project = "proj", feature = "feat")
      updateFeatureCreationDateInDB("test", featureId.get, LocalDateTime.now().minusYears(1))
      val response = situation.fetchProject("test", "proj")
      (response.json.get \ "features" \ 0 \ "stale").asOpt[JsObject]  mustBe None
    }

    "report too old never called feature as stale if stale tracking is disabled" in {
      val situation = TestSituationBuilder().withTenants(TestTenant("test")
          .withProjects(TestProject("proj")
            .withFeatureNames("feat")
          )
        ).withCustomConfiguration(Map("app.experimental.stale-tracking.enabled" -> "true"))
        .loggedInWithAdminRights()
        .build()

      val featureId = situation.findFeatureId("test", project = "proj", feature = "feat")
      updateFeatureCreationDateInDB("test", featureId.get, LocalDateTime.now().minusYears(1))
      val response = situation.fetchProject("test", "proj")
      val stale = (response.json.get \ "features" \ 0 \ "stale").as[JsObject]
      (stale \ "because").as[String] mustEqual "NeverCalled"
    }
  }

}
