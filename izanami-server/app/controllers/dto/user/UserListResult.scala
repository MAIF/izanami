package controllers.dto.user

import controllers.dto.meta.Metadata
import domains.user.{User, UserNoPasswordInstances}
import play.api.libs.json.Json

case class UserListResult(results: List[User], metadata: Metadata)

object UserListResult {
  implicit val cF     = UserNoPasswordInstances.format
  implicit val format = Json.format[UserListResult]
}
