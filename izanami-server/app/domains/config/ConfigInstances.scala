package domains.config
import domains.{AuthInfo, IsAllowed, Key}
import play.api.libs.json.Json

object ConfigInstances {

  implicit val format = Json.format[Config]

}
