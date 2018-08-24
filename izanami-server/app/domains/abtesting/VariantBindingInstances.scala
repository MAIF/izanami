package domains.abtesting
import domains.Key
import play.api.libs.json.{Format, Json, Writes}

object VariantBindingInstances {

  implicit val keyFormat: Format[VariantBindingKey] = Format(
    Key.format.map { k =>
      VariantBindingKey(k)
    },
    Writes[VariantBindingKey](vk => Key.format.writes(vk.key))
  )

  implicit val format = Json.format[VariantBinding]
}
