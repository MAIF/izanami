package domains.abtesting
import java.time.LocalDateTime

import domains.Key
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
    import ExperimentInstances._
    implicit val kf                                   = ExperimentVariantEventKeyInstances.format
    implicit val dateTimeReads: Reads[LocalDateTime]  = Reads.DefaultLocalDateTimeReads
    implicit val dateTimeWrite: Writes[LocalDateTime] = Writes.DefaultLocalDateTimeWrites
    Json.format[ExperimentVariantDisplayed]
  }
}

object ExperimentVariantWonInstances {
  implicit val format = {
    import ExperimentInstances._
    implicit val kf                                   = ExperimentVariantEventKeyInstances.format
    implicit val dateTimeReads: Reads[LocalDateTime]  = Reads.DefaultLocalDateTimeReads
    implicit val dateTimeWrite: Writes[LocalDateTime] = Writes.DefaultLocalDateTimeWrites
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
      case other => JsError("error.bad.format")
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
