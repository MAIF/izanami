package filters

import domains.AuthInfo
import play.api.libs.typedmap.TypedKey

object FilterAttrs {
  object Attrs {
    val AuthInfo: TypedKey[Option[AuthInfo]] = TypedKey("auth")
  }
}
