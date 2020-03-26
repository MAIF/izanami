package domains.abtesting.events

import java.time.LocalDateTime

import domains.Key
import domains.abtesting.ExperimentInstances._
import play.api.libs.json._

object ExperimentVariantEventKeyInstances {

  implicit val format: Format[ExperimentVariantEventKey] = Format(
    Key.format.map { k =>
      ExperimentVariantEventKey(k)
    },
    Writes[ExperimentVariantEventKey](vk => Key.format.writes(vk.key))
  )
}

object ExperimentVariantDisplayedInstances {
  implicit val format = {
    implicit val kf: Format[ExperimentVariantEventKey] = ExperimentVariantEventKeyInstances.format
    implicit val dateTimeReads: Reads[LocalDateTime]   = Reads.localDateTimeReads("yyyy-MM-dd'T'HH:mm:ss.SSS")
    implicit val dateTimeWrite: Writes[LocalDateTime]  = Writes.temporalWrites("yyyy-MM-dd'T'HH:mm:ss.SSS")
    Json.format[ExperimentVariantDisplayed]
  }
}

object ExperimentVariantWonInstances {
  implicit val format = {
    implicit val kf: Format[ExperimentVariantEventKey] = ExperimentVariantEventKeyInstances.format
    implicit val dateTimeReads: Reads[LocalDateTime]   = Reads.localDateTimeReads("yyyy-MM-dd'T'HH:mm:ss.SSS")
    implicit val dateTimeWrite: Writes[LocalDateTime]  = Writes.temporalWrites("yyyy-MM-dd'T'HH:mm:ss.SSS")
    Json.format[ExperimentVariantWon]
  }
}

object ExperimentVariantEventInstances {

  private val reads: Reads[ExperimentVariantEvent] = {

    Reads[ExperimentVariantEvent] {
      case event
          if (event \ "@type")
            .asOpt[String]
            .contains("VariantDisplayedEvent") =>
        ExperimentVariantDisplayedInstances.format.reads(event)
      case event if (event \ "@type").asOpt[String].contains("VariantWonEvent") =>
        ExperimentVariantWonInstances.format.reads(event)
      case _ => JsError("error.bad.format")
    }
  }

  private val writes: Writes[ExperimentVariantEvent] = {

    Writes[ExperimentVariantEvent] {
      case e: ExperimentVariantDisplayed =>
        ExperimentVariantDisplayedInstances.format.writes(e) ++ Json.obj("@type" -> "VariantDisplayedEvent")
      case e: ExperimentVariantWon =>
        ExperimentVariantWonInstances.format.writes(e).as[JsObject] ++ Json.obj("@type" -> "VariantWonEvent")
    }
  }

  implicit val format = Format(reads, writes)
}
