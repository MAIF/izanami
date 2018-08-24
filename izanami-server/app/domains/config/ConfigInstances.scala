package domains.config
import domains.{AuthInfo, IsAllowed, Key}
import play.api.libs.json.Json

object ConfigInstances {

  implicit val isAllowed: IsAllowed[Config] = new IsAllowed[Config] {
    override def isAllowed(value: Config)(auth: Option[AuthInfo]): Boolean = Key.isAllowed(value.id)(auth)
  }

  implicit val format = Json.format[Config]

}
