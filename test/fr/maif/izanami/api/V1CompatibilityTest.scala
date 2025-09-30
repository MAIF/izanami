package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{
  system,
  BASE_URL,
  DATE_TIME_FORMATTER,
  TestCondition,
  TestFeature,
  TestFeaturePatch,
  TestProject,
  TestSituationBuilder,
  TestTenant,
  TestUserListRule
}
import play.api.http.Status.{BAD_REQUEST, NOT_FOUND, OK}
import play.api.libs.json.{JsArray, JsBoolean, JsObject, JsTrue, JsValue}

import java.net.URI
import java.time.{Duration, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import izanami.javadsl.IzanamiClient
import izanami.commons.IzanamiException
import izanami.{ClientConfig, Strategy, Crash}
import akka.actor.ActorSystem
import org.reactivecouchbase.json.{Json, Syntax}
import org.awaitility.Awaitility.await
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

class V1CompatibilityTest extends BaseAPISpec {
  "Legacy check endpoint" should {
    "convert no strategy legacy feature to V1 format" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val response =
        situation.readFeatureAsLegacy(
          "project:foo:default-feature",
          clientId = clientId,
          clientSecret = clientSecret
        )
      response.status mustBe OK

      val json = response.json.get

      (json \ "active").as[Boolean] mustBe false
      (json \ "activationStrategy").as[String] mustEqual "NO_STRATEGY"
    }

    "convert percentage legacy feature to V1 format" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:test:percentage-feature","enabled":true,"description":"An old style percentage feature","parameters":{"percentage":75},"activationStrategy":"PERCENTAGE"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val response = situation.readFeatureAsLegacy(
        "project:test:percentage-feature",
        clientId = clientId,
        clientSecret = clientSecret
      )
      response.status mustBe OK

      val json = response.json.get

      (json \ "activationStrategy").as[String] mustEqual "PERCENTAGE"
      (json \ "parameters" \ "percentage").as[Int] mustEqual 75
    }

    "convert customer list legacy feature to V1 format" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:another:customer-list-feature","enabled":true,"description":"An old style user list feature","parameters":{"customers":["foo","bar","baz"]},"activationStrategy":"CUSTOMERS_LIST"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val response = situation.readFeatureAsLegacy(
        "project:another:customer-list-feature",
        clientId = clientId,
        clientSecret = clientSecret
      )
      response.status mustBe OK

      val json = response.json.get

      (json \ "activationStrategy").as[String] mustEqual "CUSTOMERS_LIST"
      (json \ "parameters" \ "customers")
        .as[Seq[String]] must contain theSameElementsAs Seq("foo", "bar", "baz")
    }

    "convert date range legacy feature to V1 format" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:test:date-range","enabled":true,"description":"An old style date range feature","parameters":{"from":"2023-01-01 00:00:00","to":"2023-12-31 23:59:59"},"activationStrategy":"DATE_RANGE"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val response =
        situation.readFeatureAsLegacy(
          "project:test:date-range",
          clientId = clientId,
          clientSecret = clientSecret
        )
      response.status mustBe OK

      val json = response.json.get

      (json \ "activationStrategy").as[String] mustEqual "DATE_RANGE"
      (json \ "parameters" \ "from").as[String] mustEqual "2023-01-01 00:00:00"
      (json \ "parameters" \ "to").as[String] mustEqual "2023-12-31 23:59:59"
    }

    "convert release date legacy feature to V1 format" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:baz:release-date","enabled":true,"description":"An old release date feature","parameters":{"releaseDate":"22/07/2023 14:18:11"},"activationStrategy":"RELEASE_DATE"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val response =
        situation.readFeatureAsLegacy(
          "project:baz:release-date",
          clientId = clientId,
          clientSecret = clientSecret
        )
      response.status mustBe OK

      val json = response.json.get

      (json \ "activationStrategy").as[String] mustEqual "RELEASE_DATE"
      (json \ "parameters" \ "releaseDate")
        .as[String] mustEqual "2023-07-22 14:18:11"
    }

    "convert hour range legacy feature to V1 format" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:baz:hour-range-feature","enabled":true,"description":"An old style hour range feature","parameters":{"endAt":"18:00","startAt":"08:00"},"activationStrategy":"HOUR_RANGE"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val response = situation.readFeatureAsLegacy(
        "project:baz:hour-range-feature",
        clientId = clientId,
        clientSecret = clientSecret
      )
      response.status mustBe OK

      val json = response.json.get

      (json \ "activationStrategy").as[String] mustEqual "HOUR_RANGE"
      (json \ "parameters" \ "endAt").as[String] mustEqual "18:00"
      (json \ "parameters" \ "startAt").as[String] mustEqual "08:00"
    }

    "convert script feature to V1 format" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val uuid = UUID.randomUUID()
      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      val importRest = situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          s"""{"id":"project:foo:script-feature$uuid","enabled":true,"description":"An old style inline script feature","parameters":{"type":"javascript","script":"function enabled(context, enabled, disabled, http) {  if (context.id === 'benjamin') {    return enabled();  }  return disabled();}"},"activationStrategy":"SCRIPT"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val response =
        situation.readFeatureAsLegacy(
          s"project:foo:script-feature$uuid",
          clientId = clientId,
          clientSecret = clientSecret
        )
      response.status mustBe OK

      val json = response.json.get

      (json \ "activationStrategy").as[String] mustEqual "GLOBAL_SCRIPT"
      (json \ "parameters" \ "ref").as[String] must not be empty
    }

    "return fake script feature for modern feature" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    name = "modern-feature",
                    enabled = true,
                    conditions = Set(
                      TestCondition(rule = TestUserListRule(users = Set("foo")))
                    )
                  )
                )
            )
        )
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val id = situation.findFeatureId("tenant", "project", "modern-feature")
      val response = situation.readFeatureAsLegacy(
        id.get,
        clientId = clientId,
        clientSecret = clientSecret
      )
      (response.json.get \ "activationStrategy")
        .as[String] mustEqual "GLOBAL_SCRIPT"
      response.status mustBe OK
    }
  }

  "legacy /feature endpoint" should {
    "return all authorized features when calling without pattern" in {
      val situation = TestSituationBuilder()
        .withTenantNames("tenant")
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:foo:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:baz","enabled":false,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project1:baz:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val result =
        situation.readFeaturesAsLegacy(
          clientId = clientId,
          clientSecret = clientSecret,
          pattern = None,
          active = true
        )
      result.status mustBe OK

      val json = result.json.get
      val featureArray = (json \ "results").as[JsArray]
      featureArray.value must have size 3

      val barFeature = featureArray.value
        .find(elem => (elem \ "id").as[String] == "project:foo:bar")
        .get
      (barFeature \ "active").as[Boolean] mustBe true

      val bazFeature = featureArray.value
        .find(elem => (elem \ "id").as[String] == "project:foo:baz")
        .get
      (bazFeature \ "active").as[Boolean] mustBe false

      val barFeature2 = featureArray.value
        .find(elem => (elem \ "id").as[String] == "project1:baz:bar")
        .get
      (barFeature2 \ "active").as[Boolean] mustBe true
    }

    "return activation status when requested" in {
      val situation = TestSituationBuilder()
        .withTenantNames("tenant")
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:foo:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:baz","enabled":false,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project1:baz:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val result =
        situation.readFeaturesAsLegacy(
          clientId = clientId,
          clientSecret = clientSecret,
          pattern = Some("project:*"),
          active = true
        )
      result.status mustBe OK

      val json = result.json.get
      val featureArray = (json \ "results").as[JsArray]
      featureArray.value must have size 2

      val barFeature = featureArray.value
        .find(elem => (elem \ "id").as[String] == "project:foo:bar")
        .get
      (barFeature \ "active").as[Boolean] mustBe true

      val bazFeature = featureArray.value
        .find(elem => (elem \ "id").as[String] == "project:foo:baz")
        .get
      (bazFeature \ "active").as[Boolean] mustBe false
    }

    "respect given page size pagination when there is more feature" in {
      val situation = TestSituationBuilder()
        .withTenantNames("tenant")
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:f1","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f2","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f3","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f4","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f5","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f6","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val result = situation.readFeaturesAsLegacy(
        clientId = clientId,
        clientSecret = clientSecret,
        pattern = Some("project:*"),
        active = false,
        pageSize = 5
      )
      result.status mustBe OK

      val json = result.json.get
      val featureArray = (json \ "results").as[JsArray]
      featureArray.value must have size 5

      (json \ "metadata" \ "count").as[Int] mustEqual 6
      (json \ "metadata" \ "pageSize").as[Int] mustEqual 5
      (json \ "metadata" \ "nbPages").as[Int] mustEqual 2
      (json \ "metadata" \ "page").as[Int] mustEqual 1
    }

    "respect given page index" in {
      val situation = TestSituationBuilder()
        .withTenantNames("tenant")
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:f1","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f2","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f3","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f4","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f5","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f6","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val result = situation.readFeaturesAsLegacy(
        clientId = clientId,
        clientSecret = clientSecret,
        pattern = Some("project:*"),
        active = false,
        pageSize = 2,
        page = 2
      )
      result.status mustBe OK

      val json = result.json.get
      val featureArray = (json \ "results").as[JsArray]
      featureArray.value must have size 2

      (json \ "metadata" \ "count").as[Int] mustEqual 6
      (json \ "metadata" \ "pageSize").as[Int] mustEqual 2
      (json \ "metadata" \ "nbPages").as[Int] mustEqual 3
      (json \ "metadata" \ "page").as[Int] mustEqual 2
    }

    "allow to retrieve every datum by iterating over pages" in {
      val situation = TestSituationBuilder()
        .withTenantNames("tenant")
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:f1","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f2","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f3","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f4","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f5","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f6","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      def extractIdsFromResult(json: JsValue) = {
        (json \ "results" \\ "id").map(jsv => jsv.as[String]).toSeq
      }

      val ids = (for (i <- 1 to 3) yield {
        val result = situation.readFeaturesAsLegacy(
          clientId = clientId,
          clientSecret = clientSecret,
          pattern = Some("project:*"),
          active = false,
          pageSize = 2,
          page = i
        )
        extractIdsFromResult(result.json.get)
      }).flatten

      ids.size mustEqual 6
      ids must contain theSameElementsAs Seq(
        "project:f1",
        "project:f2",
        "project:f3",
        "project:f4",
        "project:f5",
        "project:f6"
      )
    }

    "not return modern features" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant").withProjects(
            TestProject("project")
              .withFeatures(
                TestFeature("modern1", id = "project:f1"),
                TestFeature("modern2", id = "project:f2")
              )
          )
        )
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:f3","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f4","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f5","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f6","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        ),
        conflictStrategy = "OVERWRITE"
      )

      val result =
        situation.readFeaturesAsLegacy(
          clientId = clientId,
          clientSecret = clientSecret,
          pattern = Some("project:*")
        )

      val json = result.json.get
      val featureArray = (json \ "results").as[JsArray]
      featureArray.value must have size 4

      (json \ "metadata" \ "count").as[Int] mustEqual 4
    }

    "not return unauthorized features for key" in {
      val situation = TestSituationBuilder()
        .withTenantNames("tenant")
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:f1","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f2","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project2:f3","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project2:f4","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f5","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:f6","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"project:*","rights":["C","R","U","D"]}],"admin":false}""".stripMargin
        )
      )

      val result =
        situation.readFeaturesAsLegacy(
          clientId = clientId,
          clientSecret = clientSecret,
          pattern = Some("project:*")
        )

      val json = result.json.get
      val featureArray = (json \ "results").as[JsArray]
      featureArray.value must have size 4

      (json \ "metadata" \ "count").as[Int] mustEqual 4
    }
  }

  "legacy checks endpoint" should {
    "allow to pass user in body" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:another:customer-list-feature","enabled":true,"description":"An old style user list feature","parameters":{"customers":["foo","bar"]},"activationStrategy":"CUSTOMERS_LIST"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val result = situation.checkFeaturesLegacy(
        pattern = "project:another:*",
        payload = play.api.libs.json.Json.obj("id" -> "foo"),
        clientId = clientId,
        clientSecret = clientSecret
      )

      (result.json.get \ "results" \ 0 \ "active").as[Boolean] mustBe true

      val result2 = situation.checkFeaturesLegacy(
        pattern = "project:another:*",
        payload = play.api.libs.json.Json.obj("id" -> "foo2"),
        clientId = clientId,
        clientSecret = clientSecret
      )

      (result2.json.get \ "results" \ 0 \ "active").as[Boolean] mustBe false
    }
  }

  "legacy jvm client" should {
    "allow to retrieve feature activation for single feature" in {
      val system = ActorSystem.create()
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        system,
        ClientConfig
          .create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
      )

      val featureClient =
        client.featureClient(Strategy.FetchStrategy(errorStrategy = Crash))
      featureClient
        .checkFeature("project:foo:default-feature")
        .get() mustBe false
    }

    "allow to retrieve feature activation for single feature with context" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:another:customer-list-feature","enabled":true,"description":"An old style user list feature","parameters":{"customers":["foo","bar"]},"activationStrategy":"CUSTOMERS_LIST"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        ActorSystem.create(),
        ClientConfig
          .create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
      )

      val featureClient =
        client.featureClient(Strategy.FetchStrategy(errorStrategy = Crash))
      featureClient
        .checkFeature(
          "project:another:customer-list-feature",
          Json.obj(
            Syntax.$("id", "baz")
          )
        )
        .get() mustBe false

      featureClient
        .checkFeature(
          "project:another:customer-list-feature",
          Json.obj(
            Syntax.$("id", "foo")
          )
        )
        .get() mustBe true
    }

    "reject feature query for unauthorized feature" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"foo:*","rights":["C","R","U","D"]}],"admin":false}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        ActorSystem.create(),
        ClientConfig
          .create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
      )

      val featureClient =
        client.featureClient(Strategy.FetchStrategy(errorStrategy = Crash))
      val thrown = the[IzanamiException] thrownBy featureClient
        .checkFeature("project:foo:default-feature")
        .get()
      thrown.getMessage must include("403")
    }

    "allow to retrieve activation for several features" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:bar","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":false}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        ActorSystem.create(),
        ClientConfig
          .create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
      )

      val featureClient =
        client.featureClient(Strategy.FetchStrategy(errorStrategy = Crash))

      val result = featureClient.features("project:foo:*").get()
      result.isActive("project:foo:default-feature") mustBe false
      result.isActive("project:foo:bar") mustBe true
    }

    "return false for all unauthorized features" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:bar","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"foo:*","rights":["C","R","U","D"]}],"admin":false}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        ActorSystem.create(),
        ClientConfig
          .create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
      )

      val featureClient =
        client.featureClient(Strategy.FetchStrategy(errorStrategy = Crash))

      val result = featureClient.features("project:foo:*").get()
      result.isActive("project:foo:default-feature") mustBe false
      result.isActive("project:foo:bar") mustBe false
    }

    "allow to poll feature change state" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret =
        "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination(
        "tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        ActorSystem.create(),
        ClientConfig
          .create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
          .pollingBackend()
      )

      val featureClient = client.featureClient(
        Strategy.CacheWithPollingStrategy(
          patterns = Seq("project:*"),
          pollingInterval = 2000.milliseconds,
          errorStrategy = Crash
        )
      )
      featureClient
        .checkFeature("project:foo:default-feature")
        .get() mustBe false

      val feature =
        (situation.fetchProject("tenant", "project").json.get \ "features" \ 0)
          .as[JsObject]
      val updatedFeature = feature + ("enabled" -> JsTrue)
      situation.updateFeature(
        "tenant",
        "project:foo:default-feature",
        updatedFeature
      )

      await until { () =>
        featureClient.checkFeature("project:foo:default-feature").get() == true
      }
    }

    /*"allow to get feature change state via SSE (feature update)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination("tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        system,
        ClientConfig.create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
          .sseBackend()
      )

      val featureClient = client.featureClient(Strategy.CacheWithSseStrategy(
        patterns = Seq("project:*"),
        errorStrategy = Crash)
        .withPollingDisabled()
      )
      await until {() => featureClient.checkFeature("project:foo:default-feature").get() == false}

      val feature = (situation.fetchProject("tenant", "project").json.get \ "features" \ 0).as[JsObject]
      val updatedFeature = feature + ("enabled" -> JsTrue)
      situation.updateFeature("tenant", "project:foo:default-feature", updatedFeature)

      await until {() => featureClient.checkFeature("project:foo:default-feature").get() == true}
    }

    "allow to get feature change state via SSE (feature patch)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination("tenant",
        features = Seq(
          """{"id":"project:foo:default-feature1","enabled":false,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:default-feature2","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:default-feature3","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        system,
        ClientConfig.create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
          .sseBackend()
      )

      val featureClient = client.featureClient(Strategy.CacheWithSseStrategy(
          patterns = Seq("project:*"),
          errorStrategy = Crash)
        .withPollingDisabled()
      )

      await until {() => featureClient.checkFeature("project:foo:default-feature").get() == false}

      situation.patchFeatures("tenant", Seq(
        TestFeaturePatch(
          op = "replace",
          path = s"/project:foo:default-feature1/enabled",
          value = JsBoolean(true)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/project:foo:default-feature2/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "remove",
          path = s"/project:foo:default-feature3"
        )
      ))

      await until {() => featureClient.checkFeature("project:foo:default-feature1").get() == true }
      await until {() => featureClient.checkFeature("project:foo:default-feature2").get() == false }
      await until {() => featureClient.checkFeature("project:foo:default-feature3").get() == false }
    }

    "allow to get feature state change via SSE (feature delete)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination("tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        system,
        ClientConfig.create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
          .sseBackend()
      )

      val featureClient = client.featureClient(Strategy.CacheWithSseStrategy(
          patterns = Seq("project:*"),
          errorStrategy = Crash)
        .withPollingDisabled()
      )

      await until {
        () => featureClient.checkFeature("project:foo:default-feature").get() == true
      }

      situation.deleteFeature("tenant", "project:foo:default-feature")

      await until {() => featureClient.checkFeature("project:foo:default-feature").get() == false}
    }

    "allow to get feature state change via SSE (project delete)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination("tenant",
        features = Seq(
          """{"id":"project:foo:default-feature","enabled":true,"description":"An old default feature","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        system,
        ClientConfig.create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
          .sseBackend()
      )

      val featureClient = client.featureClient(Strategy.CacheWithSseStrategy(
          patterns = Seq("project:*"),
          errorStrategy = Crash)
        .withPollingDisabled()
      )

      await until {
        () => featureClient.checkFeature("project:foo:default-feature").get() == true
      }

      situation.deleteProject("project", "tenant")

      await until { () => featureClient.checkFeature("project:foo:default-feature").get() == false }
    }

    "allow to update smartcache internal state based on emitted events(hour range)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"


      situation.importAndWaitTermination("tenant",
        features = Seq(
          s"""{"id":"project:hour-range-feature","enabled":true,"description":"An old style hour range feature","parameters":{"endAt":"23:59","startAt":"00:00"},"activationStrategy":"HOUR_RANGE"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        system,
        ClientConfig.create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
          .sseBackend()
      )

      val featureClient = client.featureClient(Strategy.CacheWithSseStrategy(errorStrategy = Crash, patterns = Seq("project:*")).withPollingDisabled())
       await until {() => featureClient.checkFeature("project:hour-range-feature").get() == true}


      val feature = (situation.fetchProject("tenant", "project").json.get \ "features" \ 0).as[JsObject]

      var newCondition = (feature \ "conditions").as[JsObject] ++ play.api.libs.json.Json.obj("startTime"  -> s"23:59")
      var updatedFeature = feature + ("conditions" -> newCondition)
      situation.updateFeature("tenant", "project:hour-range-feature", updatedFeature)

      await until {() => featureClient.checkFeature("project:hour-range-feature").get() == false}

      newCondition = (feature \ "conditions").as[JsObject] ++ play.api.libs.json.Json.obj("startTime" -> s"00:00")
      updatedFeature = feature + ("conditions" -> newCondition)
      situation.updateFeature("tenant", "project:hour-range-feature", updatedFeature)

      await until { () => featureClient.checkFeature("project:hour-range-feature").get() == true }
    }

    "allow to update smartcache internal state based on emitted events (date range)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      val LEGACY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      val now = LocalDateTime.now()
      val begin = now.minusDays(2)
      val end = now.plusDays(2)
      situation.importAndWaitTermination("tenant",
        features = Seq(
          s"""{"id":"project:test:date-range","enabled":true,"description":"An old style date range feature","parameters":{"from":"${begin.format(LEGACY_FORMATTER)}","to":"${end.format(LEGACY_FORMATTER)}"},"activationStrategy":"DATE_RANGE"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        system,
        ClientConfig.create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
          .sseBackend()
      )

      val featureClient = client.featureClient(Strategy.CacheWithSseStrategy(errorStrategy = Crash, patterns = Seq("project:*")).withPollingDisabled())
      await until { () => featureClient.checkFeature("project:test:date-range").get() == true }

      val feature = (situation.fetchProject("tenant", "project").json.get \ "features" \ 0).as[JsObject]

      var newCondition = (feature \ "conditions").as[JsObject] ++ play.api.libs.json.Json.obj("begin" -> end.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
      var updatedFeature = feature + ("conditions" -> newCondition)
      situation.updateFeature("tenant", "project:test:date-range", updatedFeature)

      await until { () => featureClient.checkFeature("project:test:date-range").get() == false }

      newCondition = (feature \ "conditions").as[JsObject] ++ play.api.libs.json.Json.obj("begin" -> begin.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
      updatedFeature = feature + ("conditions" -> newCondition)
      situation.updateFeature("tenant", "project:test:date-range", updatedFeature)

      await until { () => featureClient.checkFeature("project:test:date-range").get() == true }
    }

    "allow to update smartcache internal state based on emitted events (release date)" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      val LEGACY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      val now = LocalDateTime.now()
      val begin = now.minusDays(2)
      val afterNow = now.plusDays(2)
      situation.importAndWaitTermination("tenant",
        features = Seq(
          s"""{"id":"project:release-date","enabled":true,"description":"An old release date feature","parameters":{"releaseDate":"${begin.format(LEGACY_FORMATTER)}"},"activationStrategy":"RELEASE_DATE"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val client = IzanamiClient.client(
        system,
        ClientConfig.create("http://localhost:9000")
          .withClientId(clientId)
          .withClientSecret(clientSecret)
          .sseBackend()
      )

      val featureClient = client.featureClient(Strategy.CacheWithSseStrategy(errorStrategy = Crash, patterns = Seq("project:*")).withPollingDisabled())
      await until { () => featureClient.checkFeature("project:release-date").get() == true }

      val feature = (situation.fetchProject("tenant", "project").json.get \ "features" \ 0).as[JsObject]

      var newCondition = (feature \ "conditions").as[JsObject] ++ play.api.libs.json.Json.obj("begin" -> afterNow.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
      var updatedFeature = feature + ("conditions" -> newCondition)
      situation.updateFeature("tenant", "project:release-date", updatedFeature)

      await until { () => featureClient.checkFeature("project:release-date").get() == false }

      newCondition = (feature \ "conditions").as[JsObject] ++ play.api.libs.json.Json.obj("begin" -> begin.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
      updatedFeature = feature + ("conditions" -> newCondition)
      situation.updateFeature("tenant", "project:release-date", updatedFeature)

      await until { () => featureClient.checkFeature("project:release-date").get() == true }
    }*/
  }

  "event endpoint" should {
    /*"filter events to keep only those authorized by provided key" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination("tenant",
        features = Seq(
          """{"id":"project:foo:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:baz","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project1:baz:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"project:*","rights":["C","R","U","D"]}],"admin":false}""".stripMargin
        )
      )

      val msgs = ArrayBuffer[Any]()
      EventSource(
        uri = Uri(s"$BASE_URL/events"),
        send = { req =>
          Http().singleRequest(req.withHeaders(req.headers ++ Seq(
            RawHeader("Izanami-Client-Id", clientId),
            RawHeader("Izanami-Client-Secret", clientSecret)
          )))
        },
        initialLastEventId = None,
        retryDelay = 2.seconds
      )
        .map(sse => play.api.libs.json.Json.parse(sse.data))
        .filter(json => (json \ "type").asOpt[String].forall(str => str == "FEATURE_UPDATED"))
        .map(json => {
          msgs.addOne(json)
          json
        })
        .run()

      Thread.sleep(5000)

      situation.patchFeatures("tenant", Seq(
        TestFeaturePatch(
          op = "replace",
          path = s"/project:foo:bar/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/project:foo:baz/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/project1:baz:bar/enabled",
          value = JsBoolean(false)
        )
      ))


      await.pollDelay(Duration.ofSeconds(3)) until { () => {
        msgs.size == 2
      }
      }
    }

    "filter events to keep only those authorized by provided key if key is admin" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination("tenant",
        features = Seq(
          """{"id":"project:foo:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:foo:baz","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project1:baz:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"foo:*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val msgs = ArrayBuffer[Any]()
      EventSource(
        uri = Uri(s"$BASE_URL/events"),
        send = { req =>
          Http().singleRequest(req.withHeaders(req.headers ++ Seq(
            RawHeader("Izanami-Client-Id", clientId),
            RawHeader("Izanami-Client-Secret", clientSecret)
          )))
        },
        initialLastEventId = None,
        retryDelay = 2.seconds
      )
        .map(sse => play.api.libs.json.Json.parse(sse.data))
        .filter(json => (json \ "type").asOpt[String].forall(str => str == "FEATURE_UPDATED"))
        .map(json => {
          msgs.addOne(json)
          json
        })
        .run()

      Thread.sleep(5000)

      situation.patchFeatures("tenant", Seq(
        TestFeaturePatch(
          op = "replace",
          path = s"/project:foo:bar/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/project:foo:baz/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/project1:baz:bar/enabled",
          value = JsBoolean(false)
        )
      ))


      await.pollDelay(Duration.ofSeconds(3)) until { () => {
        msgs.size == 3
      }
      }
    }

    "filter event to keep only the ones matching given pattern" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant"))
        .loggedInWithAdminRights()
        .build()

      val clientId = "yfsc5ooy3v3hu5z2"
      val clientSecret = "sygl4ls9sjr93v1p9ufc7y8p83117w1f3t2p6nh8w15b7njfoz9er4sgjgabkxmw"

      situation.importAndWaitTermination("tenant",
        features = Seq(
          """{"id":"project:foo:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:baz:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"project:fifou:buzz","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"another:foo:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin,
          """{"id":"another:baz:bar","enabled":true,"description":"","parameters":{},"activationStrategy":"NO_STRATEGY"}""".stripMargin
        ),
        keys = Seq(
          s"""{"clientId":"$clientId","name":"local create read key","clientSecret":"$clientSecret","authorizedPatterns":[{"pattern":"foo:*","rights":["C","R","U","D"]}],"admin":true}""".stripMargin
        )
      )

      val msgs = ArrayBuffer[Any]()
      EventSource(
        uri = Uri(s"$BASE_URL/events?pattern=project:*"),
        send = { req =>
          Http().singleRequest(req.withHeaders(req.headers ++ Seq(
            RawHeader("Izanami-Client-Id", clientId),
            RawHeader("Izanami-Client-Secret", clientSecret)
          )))
        },
        initialLastEventId = None,
        retryDelay = 2.seconds
      )
        .map(sse => play.api.libs.json.Json.parse(sse.data))
        .filter(json => (json \ "type").asOpt[String].forall(str => str == "FEATURE_UPDATED"))
        .map(json => {
          msgs.addOne(json)
          json
        })
        .run()

      Thread.sleep(5000)

      situation.patchFeatures("tenant", Seq(
        TestFeaturePatch(
          op = "replace",
          path = s"/project:foo:bar/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/project:baz:bar/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/project:fifou:buzz/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/another:foo:bar/enabled",
          value = JsBoolean(false)
        ),
        TestFeaturePatch(
          op = "replace",
          path = s"/another:baz:bar/enabled",
          value = JsBoolean(false)
        )
      ))

      await.pollDelay(Duration.ofSeconds(1)) until {() => {
        msgs.size == 3
      }}
    }*/
  }
}
