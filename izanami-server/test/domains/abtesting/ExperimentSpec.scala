package domains.abtesting
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoUnit, TemporalUnit}

import cats.data.NonEmptyList
import cats.data.Validated.Valid
import domains.Key
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.{JsSuccess, Json}
import store.Result.AppErrors
import test.IzanamiSpec

class ExperimentSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience {
  import ExperimentInstances._

  "Experiment" must {

    "Variant must not have changed if variant name changes" in {

      val old = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.5))
        )
      )
      val data = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A' ", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.5))
        )
      )
      Experiment.isTrafficChanged(old, data) mustBe false
    }

    "Variant must have changed if nb variant is different" in {

      val old = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name", traffic = Traffic(0.5))
        )
      )
      val data = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.3))
        )
      )
      Experiment.isTrafficChanged(old, data) mustBe true
    }

    "Variant must have changed if nb variant are differents" in {

      val old = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name", traffic = Traffic(0.5))
        )
      )
      val data = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "B", name = "name", traffic = Traffic(0.5)),
          Variant(id = "C", name = "name", traffic = Traffic(0.5))
        )
      )
      Experiment.isTrafficChanged(old, data) mustBe true
    }

    "Variant must have changed if traffic changes" in {

      val old = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name", traffic = Traffic(0.5))
        )
      )
      val data = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.3)),
          Variant(id = "B", name = "name", traffic = Traffic(0.5))
        )
      )
      Experiment.isTrafficChanged(old, data) mustBe true
    }

    "simple serialization" in {
      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A", traffic = Traffic(0.4)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.6))
        )
      )

      Json.toJson(experiment) mustBe Json.parse("""
          |{
          |  "id" : "test",
          |  "name" : "name",
          |  "enabled" : true,
          |  "variants" : [ {
          |    "id" : "A",
          |    "name" : "name A",
          |    "traffic" : 0.4
          |  }, {
          |    "id" : "B",
          |    "name" : "name B",
          |    "traffic" : 0.6
          |  } ]
          |}
        """.stripMargin)

    }

    "complex serialization" in {
      val from = LocalDateTime.now()
      val to   = LocalDateTime.now()

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(ClosedCampaign(from, to, "A")),
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )

      Json.toJson(experiment) mustBe Json.parse(s"""
          |{
          |  "id" : "test",
          |  "name" : "name",
          |  "description" : "desc",
          |  "enabled" : true,
          |  "campaign": {
          |    "from": "${DateTimeFormatter.ISO_DATE_TIME.format(from)}",
          |    "to": "${DateTimeFormatter.ISO_DATE_TIME.format(to)}",
          |    "won": "A"
          |  },
          |  "variants" : [ {
          |    "id" : "A",
          |    "name" : "name A",
          |    "description" : "desc A",
          |    "traffic" : 0.4,
          |    "currentPopulation" : 5
          |  }, {
          |    "id" : "B",
          |    "name" : "name B",
          |    "description" : "desc A",
          |    "traffic" : 0.6,
          |    "currentPopulation" : 6
          |  } ]
          |}
        """.stripMargin)

    }

    "simple deserialization" in {
      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A", traffic = Traffic(0.4)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.6))
        )
      )

      val json = Json.parse("""
           |{
           |  "id" : "test",
           |  "name" : "name",
           |  "enabled" : true,
           |  "variants" : [ {
           |    "id" : "A",
           |    "name" : "name A",
           |    "traffic" : 0.4
           |  }, {
           |    "id" : "B",
           |    "name" : "name B",
           |    "traffic" : 0.6
           |  } ]
           |}
         """.stripMargin)

      json.validate[Experiment] mustBe JsSuccess(experiment)

    }

    "complex deserialization" in {
      val from = LocalDateTime.now()
      val to   = LocalDateTime.now()

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(ClosedCampaign(from, to, "A")),
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )

      val json = Json.parse(s"""
           |{
           |  "id" : "test",
           |  "name" : "name",
           |  "description" : "desc",
           |  "enabled" : true,
           |  "campaign": {
           |    "from": "${DateTimeFormatter.ISO_DATE_TIME.format(from)}",
           |    "to": "${DateTimeFormatter.ISO_DATE_TIME.format(to)}",
           |    "won": "A"
           |  },
           |  "variants" : [ {
           |    "id" : "A",
           |    "name" : "name A",
           |    "description" : "desc A",
           |    "traffic" : 0.4,
           |    "currentPopulation" : 5
           |  }, {
           |    "id" : "B",
           |    "name" : "name B",
           |    "description" : "desc A",
           |    "traffic" : 0.6,
           |    "currentPopulation" : 6
           |  } ]
           |}
         """.stripMargin)

      json.validate[Experiment] mustBe JsSuccess(experiment)
    }

    "Traffic must be 100 %" in {
      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )

      Experiment.validate(experiment) mustBe Right(experiment)
    }

    "Validation fail if traffic is not 100 %" in {
      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.5),
                  currentPopulation = Some(6))
        )
      )

      Experiment.validate(experiment) mustBe Left(AppErrors.error("error.traffic.not.cent.percent"))
    }

    "Validation fail if campaign date are wrong" in {
      val from = LocalDateTime.now().plus(2, ChronoUnit.MONTHS)
      val to   = LocalDateTime.now()

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(ClosedCampaign(from, to, "A")),
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )

      Experiment.validate(experiment) mustBe Left(AppErrors.error("error.campaign.date.invalid"))
    }
  }

}
