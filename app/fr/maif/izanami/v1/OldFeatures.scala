package fr.maif.izanami.v1

import fr.maif.izanami.models.features._
import fr.maif.izanami.models.{CompleteFeature, CompleteWasmFeature, LightWeightWasmFeature, SingleConditionFeature}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.OldFeatureType._
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.web.ImportController.scriptIdToNodeCompatibleName
import io.otoroshi.wasm4s.scaladsl.WasmSource
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind.Wasmo
import play.api.libs.functional.syntax.{toApplicativeOps, toFunctionalBuilderOps}
import play.api.libs.json.Reads.{localDateTimeReads, localTimeReads, max, min}
import play.api.libs.json._

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, LocalTime, ZoneId}

sealed trait OldFeature {
  def id: String
  def name: String
  def enabled: Boolean
  def description: Option[String]
  def tags: Set[String]
  def toFeature(
      project: String,
      zone: ZoneId,
      globalScriptById: Map[String, OldGlobalScript]
  ): Either[String, (CompleteFeature, Option[OldScript])] = {
    this match {
      case OldDefaultFeature(id, name, enabled, description, tags, _)                   =>
        Right(
          (
            SingleConditionFeature(
              id = id,
              name = Option(name).getOrElse(id),
              enabled = enabled,
              project = project,
              condition = All,
              description = description.getOrElse(""),
              tags = tags
            ),
            None
          )
        )
      case OldDateRangeFeature(id, name, enabled, description, tags, from, to, _)       =>
        Right(
          (
            SingleConditionFeature(
              id = id,
              name = Option(name).getOrElse(id),
              enabled = enabled,
              project = project,
              condition = DateRangeActivationCondition(
                begin = Option(from.atZone(zone).toInstant),
                end = Option(to.atZone(zone).toInstant),
                timezone = zone
              ),
              description = description.getOrElse(""),
              tags = tags
            ),
            None
          )
        )
      case OldReleaseDateFeature(id, name, enabled, description, tags, date, _)         =>
        Right(
          (
            SingleConditionFeature(
              id = id,
              name = Option(name).getOrElse(id),
              enabled = enabled,
              project = project,
              condition = DateRangeActivationCondition(begin = Option(date.atZone(zone).toInstant), timezone = zone),
              description = description.getOrElse(""),
              tags = tags
            ),
            None
          )
        )
      case OldHourRangeFeature(id, name, enabled, description, tags, startAt, endAt, _) =>
        Right(
          (
            SingleConditionFeature(
              id = id,
              name = Option(name).getOrElse(id),
              enabled = enabled,
              project = project,
              condition =
                ZonedHourPeriod(timezone = zone, hourPeriod = HourPeriod(startTime = startAt, endTime = endAt)),
              description = description.getOrElse(""),
              tags = tags
            ),
            None
          )
        )
      case OldPercentageFeature(id, name, enabled, description, tags, percentage)       =>
        Right(
          (
            SingleConditionFeature(
              id = id,
              name = Option(name).getOrElse(id),
              enabled = enabled,
              project = project,
              condition = UserPercentage(percentage = percentage),
              description = description.getOrElse(""),
              tags = tags
            ),
            None
          )
        )
      case OldCustomersFeature(id, name, enabled, description, tags, customers)         =>
        Right(
          (
            SingleConditionFeature(
              id = id,
              name = Option(name).getOrElse(id),
              enabled = enabled,
              project = project,
              condition = UserList(users = customers.toSet),
              description = description.getOrElse(""),
              tags = tags
            ),
            None
          )
        )
      case OldScriptFeature(id, name, enabled, description, tags, script)               =>
        Right(
          (
            CompleteWasmFeature(
              id = id,
              name = Option(name).getOrElse(id),
              project = project,
              enabled = enabled,
              description = description.getOrElse(""),
              tags = tags,
              wasmConfig = WasmConfig(
                name = s"${id}_script",
                // TODO release script &  remove -dev
                source = WasmSource(kind = null, path = "TODO", opts = Json.obj()),
                functionName = Some("execute"),
                wasi = true
              ),
              resultType = BooleanResult
            ),
            Some(script)
          )
        )
      case OldGlobalScriptFeature(id, name, enabled, description, tags, ref)            => {
        globalScriptById.get(scriptIdToNodeCompatibleName(ref)) match {
          case None                                                                   => Left(s"Can't find referenced global script ${ref}")
          case Some(OldGlobalScript(scriptId, scriptName, scriptDescription, source)) => {
            Right(
              (
                CompleteWasmFeature(
                  id = id,
                  name = Option(name).getOrElse(id),
                  project = project,
                  enabled = enabled,
                  description = description.getOrElse(""),
                  tags = tags,
                  wasmConfig = WasmConfig(
                    name = scriptId,
                    // TODO release script &  remove -dev
                    source = WasmSource(kind = Wasmo, path = "TODO", opts = Json.obj()),
                    functionName = Some("execute"),
                    wasi = true
                  ),
                  resultType = BooleanResult
                ),
                None
              )
            )
          }
        }
      }
    }
  }
}

sealed trait OldPluginLangage
case object JavaScript extends OldPluginLangage
case object Kotlin     extends OldPluginLangage
case object Scala      extends OldPluginLangage

case class OldDefaultFeature(
    id: String,
    name: String,
    enabled: Boolean,
    description: Option[String],
    tags: Set[String],
    parameters: JsValue = JsNull
) extends OldFeature

case class OldScript(language: OldPluginLangage, script: String)
case class OldGlobalScript(id: String, name: String, description: Option[String], source: OldScript)

case class OldGlobalScriptFeature(
    id: String,
    name: String,
    enabled: Boolean,
    description: Option[String],
    tags: Set[String],
    ref: String
) extends OldFeature

case class OldScriptFeature(
    id: String,
    name: String,
    enabled: Boolean,
    description: Option[String],
    tags: Set[String],
    script: OldScript
) extends OldFeature

case class OldDateRangeFeature(
    id: String,
    name: String,
    enabled: Boolean,
    description: Option[String],
    tags: Set[String],
    from: LocalDateTime,
    to: LocalDateTime,
    timezone: ZoneId = null
) extends OldFeature

case class OldReleaseDateFeature(
    id: String,
    name: String,
    enabled: Boolean,
    description: Option[String],
    tags: Set[String],
    releaseDate: LocalDateTime,
    timezone: ZoneId = null
) extends OldFeature

case class OldHourRangeFeature(
    id: String,
    name: String,
    enabled: Boolean,
    description: Option[String],
    tags: Set[String],
    startAt: LocalTime,
    endAt: LocalTime,
    timezone: ZoneId = null
) extends OldFeature

case class OldPercentageFeature(
    id: String,
    name: String,
    enabled: Boolean,
    description: Option[String],
    tags: Set[String],
    percentage: Int
) extends OldFeature

case class OldCustomersFeature(
    id: String,
    name: String,
    enabled: Boolean,
    description: Option[String],
    tags: Set[String],
    customers: List[String]
) extends OldFeature

object OldFeatureType {
  val NO_STRATEGY    = "NO_STRATEGY"
  val RELEASE_DATE   = "RELEASE_DATE"
  val DATE_RANGE     = "DATE_RANGE"
  val SCRIPT         = "SCRIPT"
  val GLOBAL_SCRIPT  = "GLOBAL_SCRIPT"
  val PERCENTAGE     = "PERCENTAGE"
  val HOUR_RANGE     = "HOUR_RANGE"
  val CUSTOMERS_LIST = "CUSTOMERS_LIST"
}

object OldFeature {
  private val timePattern      = "HH:mm"
  private val timePattern2     = "H:mm"
  private val dateTimePattern  = "dd/MM/yyyy HH:mm:ss"
  private val dateTimePattern2 = "dd/MM/yyyy HH:mm"
  private val dateTimePattern3 = "yyyy-MM-dd HH:mm:ss"

  def fromModernFeature(f: SingleConditionFeature): OldFeature = {
    f.condition match {
      case DateRangeActivationCondition(begin, end, timezone) => {
        {
          for (
            b <- begin;
            e <- end
          )
            yield OldDateRangeFeature(
              id = f.id,
              name = f.name,
              enabled = f.enabled,
              description = Option(f.description),
              from = b.atZone(timezone).toLocalDateTime,
              to = e.atZone(timezone).toLocalDateTime,
              timezone = timezone,
              tags = f.tags
            )
        }.orElse(
          begin.map(b =>
            OldReleaseDateFeature(
              id = f.id,
              name = f.name,
              enabled = f.enabled,
              description = Option(f.description),
              releaseDate = b.atZone(timezone).toLocalDateTime,
              timezone = timezone,
              tags = f.tags
            )
          )
        ).getOrElse(
          throw new RuntimeException(
            "Failed to convert SingleConditionFeature to OldFeature, this should not happen, please file an issue"
          )
        )
      }
      case ZonedHourPeriod(HourPeriod(start, end), timezone)  =>
        OldHourRangeFeature(
          id = f.id,
          name = f.name,
          enabled = f.enabled,
          description = Option(f.description),
          startAt = start,
          endAt = end,
          timezone = timezone,
          tags = f.tags
        )
      case UserPercentage(percentage)                         =>
        OldPercentageFeature(
          id = f.id,
          name = f.name,
          enabled = f.enabled,
          description = Some(f.description),
          percentage = percentage,
          tags = f.tags
        )
      case UserList(users)                                    =>
        OldCustomersFeature(
          id = f.id,
          name = f.name,
          enabled = f.enabled,
          description = Option(f.description),
          customers = users.toList,
          tags = f.tags
        )
      case All                                                =>
        OldDefaultFeature(
          f.id,
          f.name,
          f.enabled,
          description = Option(f.description),
          parameters = Json.obj(),
          tags = f.tags
        )
    }
  }

  def fromScriptFeature[E ](f: LightWeightWasmFeature): OldFeature = {
    OldGlobalScriptFeature(
      f.id,
      name = f.name,
      enabled = f.enabled,
      description = Some(f.description),
      tags = f.tags,
      ref = f.wasmConfigName
    )
  }

  def fromScriptFeature[E ](f: CompleteWasmFeature): OldFeature = {
    OldGlobalScriptFeature(
      f.id,
      name = f.name,
      enabled = f.enabled,
      description = Some(f.description),
      tags = f.tags,
      ref = f.wasmConfig.name
    )
  }

  def commonRead =
    (__ \ "id").read[String] and
    (__ \ "name").readWithDefault[String]("null") and
    (__ \ "enabled").read[Boolean].orElse(Reads.pure(false)) and
    (__ \ "description").readNullable[String] and
    (__ \ "tags").readWithDefault[Set[String]](Set[String]())

  implicit val percentageReads: Reads[OldPercentageFeature] = (
    commonRead and
      (__ \ "parameters" \ "percentage").read[Int](min(0) keepAnd max(100))
  )(OldPercentageFeature.apply _)

  implicit val customerReads: Reads[OldCustomersFeature] = (
    commonRead and
      (__ \ "parameters" \ "customers").read[List[String]]
  )(OldCustomersFeature.apply _)

  implicit val hourRangeReads: Reads[OldHourRangeFeature] = (
    commonRead and
      (__ \ "parameters" \ "startAt")
        .read[LocalTime](localTimeReads(timePattern2))
        .orElse(localTimeReads(timePattern)) and
      (__ \ "parameters" \ "endAt")
        .read[LocalTime](localTimeReads(timePattern2))
        .orElse(localTimeReads(timePattern))
  )((id, name, enabled, description, tags, start, end) =>
    OldHourRangeFeature(id, name, enabled, description, tags, start, end)
  )

  implicit val defaultFeatureReads: Reads[OldDefaultFeature] = (
    commonRead and
      (__ \ "parameters").readNullable[JsValue].map(_.getOrElse(JsNull))
  )(OldDefaultFeature.apply _)

  implicit val globalScriptFeatureReads: Reads[OldGlobalScriptFeature] = (
    commonRead and
      (__ \ "parameters" \ "ref").read[String]
  )(OldGlobalScriptFeature.apply _)

  implicit val langageRead: Reads[OldPluginLangage] = json => {
    json
      .asOpt[String]
      .map(_.toUpperCase)
      .map(l =>
        l match {
          case "JAVASCRIPT" => JsSuccess(JavaScript)
          case "SCALA"      => JsSuccess(Scala)
          case "KOTLIN"     => JsSuccess(Kotlin)
          case _            => JsError(s"Unknown plugin langage $l")
        }
      )
      .getOrElse(JsError(s"Missing langage for plugin"))
  }

  implicit val localScriptRead: Reads[OldScript] = (
    (__ \ "type").read[OldPluginLangage] and
      (__ \ "script").read[String]
  )(OldScript.apply _)

  implicit val scriptFeatureReads: Reads[OldScriptFeature] = (
    commonRead and
      (__ \ "parameters").read[OldScript](localScriptRead)
  )(OldScriptFeature.apply _)

  implicit val globalScriptReads: Reads[OldGlobalScript] = (
    (__ \ "id").read[String] and
      (__ \ "name").read[String] and
      (__ \ "description").readNullable[String] and
      (__ \ "source").read[OldScript](localScriptRead)
  )(OldGlobalScript.apply _)

  implicit val dateRangeReads: Reads[OldDateRangeFeature] = (
    commonRead and
      (__ \ "parameters" \ "from")
        .read[LocalDateTime](localDateTimeReads(dateTimePattern3)) and
      (__ \ "parameters" \ "to")
        .read[LocalDateTime](localDateTimeReads(dateTimePattern3))
  )((id, name, enabled, description, tags, from, to) =>
    OldDateRangeFeature(
      id = id,
      name = name,
      enabled = enabled,
      description = description,
      tags = tags,
      from = from,
      to = to
    )
  )

  implicit val releaseDateReads: Reads[OldReleaseDateFeature] = (
    commonRead and
      (__ \ "parameters" \ "releaseDate")
        .read[LocalDateTime](
          localDateTimeReads(dateTimePattern)
            .orElse(localDateTimeReads(dateTimePattern2))
            .orElse(localDateTimeReads(dateTimePattern3))
        )
  )((id, name, enabled, description, tags, releaseDate) =>
    OldReleaseDateFeature(
      id = id,
      name = name,
      enabled = enabled,
      description = description,
      releaseDate = releaseDate,
      tags = tags
    )
  )

  val oldFeatureReads: Reads[OldFeature] = { json =>
    (json \ "activationStrategy")
      .asOpt[String]
      .map {
        case NO_STRATEGY    => JsSuccess(json.as[OldDefaultFeature])
        case RELEASE_DATE   => JsSuccess(json.as[OldReleaseDateFeature])
        case DATE_RANGE     => JsSuccess(json.as[OldDateRangeFeature])
        case SCRIPT         => JsSuccess(json.as[OldScriptFeature])
        case GLOBAL_SCRIPT  => JsSuccess(json.as[OldGlobalScriptFeature])
        case PERCENTAGE     => JsSuccess(json.as[OldPercentageFeature])
        case HOUR_RANGE     => JsSuccess(json.as[OldHourRangeFeature])
        case CUSTOMERS_LIST => JsSuccess(json.as[OldCustomersFeature])
        case _              => JsError("Bad feature strategy")
      }
      .getOrElse(JsError("Bad feature format"))
  }

  def commonWrite(feature: OldFeature): JsObject = {
    Json
      .obj(
        "id"      -> feature.id,
        "name"    -> feature.name,
        "enabled" -> feature.enabled,
        "tags"    -> feature.tags
      )
      .applyOnWithOpt(feature.description)((json, desc) => json ++ Json.obj("description" -> desc))
  }

  implicit val oldDefaultFeatureWrites: Writes[OldDefaultFeature] = feature => {
    commonWrite(feature) ++ Json.obj(
      "activationStrategy" -> NO_STRATEGY,
      "parameters"         -> Json.obj()
    )
  }

  implicit val oldGlobalScriptWrites: Writes[OldGlobalScriptFeature] = feature => {
    commonWrite(feature) ++ Json.obj(
      "activationStrategy" -> GLOBAL_SCRIPT,
      "parameters"         -> Json.obj("ref" -> feature.ref)
    )
  }

  implicit val oldPercentageFeatureWrites: Writes[OldPercentageFeature] = feature => {
    commonWrite(feature) ++ Json.obj(
      "activationStrategy" -> PERCENTAGE,
      "parameters"         -> Json.obj(
        "percentage" -> feature.percentage
      )
    )
  }

  implicit val oldCustomerFeatureWrites: Writes[OldCustomersFeature] = feature => {
    commonWrite(feature) ++ Json.obj(
      "activationStrategy" -> CUSTOMERS_LIST,
      "parameters"         -> Json.obj(
        "customers" -> feature.customers
      )
    )
  }

  val dateFormatter = DateTimeFormatter.ofPattern(dateTimePattern3)

  implicit val oldReleaseDateFeatureWrites: Writes[OldReleaseDateFeature] = feature => {
    commonWrite(feature) ++ Json.obj(
      "activationStrategy" -> RELEASE_DATE,
      "parameters"         -> Json.obj(
        "releaseDate" -> feature.releaseDate.format(dateFormatter)
      )
    )
  }

  implicit val oldDateRangeFeatureWrites: Writes[OldDateRangeFeature] = feature => {
    commonWrite(feature) ++ Json.obj(
      "activationStrategy" -> DATE_RANGE,
      "parameters"         -> Json.obj(
        "from" -> feature.from.format(dateFormatter),
        "to"   -> feature.to.format(dateFormatter)
      )
    )
  }

  val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  implicit val oldHourRangeWrites: Writes[OldHourRangeFeature] = feature => {
    commonWrite(feature) ++ Json.obj(
      "activationStrategy" -> HOUR_RANGE,
      "parameters"         -> Json.obj(
        "startAt" -> feature.startAt.format(timeFormatter),
        "endAt"   -> feature.endAt.format(timeFormatter)
      )
    )
  }

  val oldFeatureWrites: Writes[OldFeature] = {
    case f: OldDefaultFeature      => oldDefaultFeatureWrites.writes(f)
    case f: OldGlobalScriptFeature => oldGlobalScriptWrites.writes(f)
    case f: OldScriptFeature       =>
      throw new RuntimeException("Failed to write OldGlobalScriptFeature, this should no happen")
    case f: OldDateRangeFeature    => oldDateRangeFeatureWrites.writes(f)
    case f: OldReleaseDateFeature  => oldReleaseDateFeatureWrites.writes(f)
    case f: OldHourRangeFeature    => oldHourRangeWrites.writes(f)
    case f: OldPercentageFeature   => oldPercentageFeatureWrites.writes(f)
    case f: OldCustomersFeature    => oldCustomerFeatureWrites.writes(f)
  }
}
