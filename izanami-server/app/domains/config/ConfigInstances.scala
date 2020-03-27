package domains.config
import domains.{IsAllowed, Key}
import domains.auth.AuthInfo
import play.api.libs.json.Json

object ConfigInstances {

  implicit val format = Json.format[Config]

}
