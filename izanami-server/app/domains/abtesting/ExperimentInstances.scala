package domains.abtesting
import java.time.LocalDateTime

import domains.{AuthInfo, IsAllowed, Key}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import play.api.libs.json.Writes.temporalWrites

object ExperimentInstances {
  import libs.json._
  // Traffic
  val trafficReads: Reads[Traffic] = {
    import Ordering.Double.TotalOrdering
    __.read[Double](min(0.0) keepAnd max(1.0)).map(Traffic.apply)
  }
  val trafficWrites: Writes[Traffic] = Writes[Traffic] { t =>
    JsNumber(t.traffic)
  }
  implicit val trafficFormat: Format[Traffic] = Format(trafficReads, trafficWrites)
  implicit val trafficEq: cats.Eq[Traffic]    = cats.Eq.fromUniversalEquals

  private val datePattern = "yyyy-MM-dd HH:mm:ss"
  private val currentCampaignFormat = {
    implicit val dateFormat: Format[LocalDateTime] =
      Format(localDateTimeReads(datePattern), temporalWrites[LocalDateTime, String](datePattern))
    Json.format[CurrentCampaign]
  }
  private val closedCampaignFormat = {
    implicit val dateFormat: Format[LocalDateTime] =
      Format(localDateTimeReads(datePattern), temporalWrites[LocalDateTime, String](datePattern))
    Json.format[ClosedCampaign]
  }
  private val campaignReads: Reads[Campaign] = Reads {
    case js: JsObject if (js \ "won").asOpt[String].isDefined => closedCampaignFormat.reads(js)
    case js: JsObject                                         => currentCampaignFormat.reads(js)
    case _                                                    => JsError("jsobject.expected")
  }
  private val campaignWrite: Writes[Campaign] = Writes {
    case c: CurrentCampaign => currentCampaignFormat.writes(c)
    case c: ClosedCampaign  => closedCampaignFormat.writes(c)
  }
  implicit val campaignFormat: Format[Campaign] = Format(campaignReads, campaignWrite)
  implicit val campaignEq: cats.Eq[Campaign]    = cats.Eq.fromUniversalEquals

  implicit val variantFormat: Format[Variant] = Json.format[Variant]
  implicit val variantEq: cats.Eq[Variant]    = cats.Eq.fromUniversalEquals

  implicit val isAllowed: IsAllowed[Experiment] = new IsAllowed[Experiment] {
    override def isAllowed(value: Experiment)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value.id.key)(auth)
  }
  implicit val experimentResultEventFormat: Format[ExperimentResultEvent] = Json.format[ExperimentResultEvent]
  implicit val format: Format[Experiment]                                 = Json.format[Experiment]
  implicit val experimentEq: cats.Eq[Experiment]                          = cats.Eq.fromUniversalEquals

  implicit val variantResultFormat: Format[VariantResult] = {
    implicit val eveFormat: Format[ExperimentVariantEvent] = ExperimentVariantEventInstances.format
    Json.format[VariantResult]
  }

  implicit val experimentResultFormat: Format[ExperimentResult] = Json.format[ExperimentResult]
}
