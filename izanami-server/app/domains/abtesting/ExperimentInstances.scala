package domains.abtesting
import domains.{AuthInfo, IsAllowed, Key}
import play.api.libs.json.Json

object ExperimentInstances {

  implicit val variantFormat = Json.format[Variant]

  implicit val isAllowed: IsAllowed[Experiment] = new IsAllowed[Experiment] {
    override def isAllowed(value: Experiment)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value.id.key)(auth)
  }

  implicit val format = Json.format[Experiment]

}
