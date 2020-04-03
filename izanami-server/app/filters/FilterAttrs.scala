package filters

import play.api.libs.typedmap.TypedKey

object FilterAttrs {
  object Attrs {
    val AuthInfo: TypedKey[Option[domains.auth.AuthInfo.Service]] = TypedKey("auth")
  }
}
