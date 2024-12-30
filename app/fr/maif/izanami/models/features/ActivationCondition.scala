package fr.maif.izanami.models.features

import fr.maif.izanami.models.Feature
import fr.maif.izanami.models.RequestContext
import fr.maif.izanami.models.features.ActivationCondition.hourFormatter
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.Reads.instantReads
import play.api.libs.json._

import java.time._
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

sealed trait ActivationCondition {
  def period: FeaturePeriod
  def rule: ActivationRule
  def active(requestContext: RequestContext, featureId: String): Boolean =
    period.active(requestContext) && rule.active(requestContext, featureId)
}

sealed trait ValuedActivationCondition extends ActivationCondition {
  def jsonValue: JsValue
}

case class NumberActivationCondition(period: FeaturePeriod = FeaturePeriod(), rule: ActivationRule = All, value: BigDecimal)
    extends ValuedActivationCondition {
  override def jsonValue: JsValue = JsNumber(value)
}

case class StringActivationCondition(period: FeaturePeriod = FeaturePeriod(), rule: ActivationRule = All, value: String)
    extends ValuedActivationCondition {
  override def jsonValue: JsValue = JsString(value)
}

case class BooleanActivationCondition(period: FeaturePeriod = FeaturePeriod(), rule: ActivationRule = All)
    extends ActivationCondition

case class FeaturePeriod(
    begin: Option[Instant] = None,
    end: Option[Instant] = None,
    hourPeriods: Set[HourPeriod] = Set(),
    days: Option[ActivationDayOfWeeks] = None,
    timezone: ZoneId = ZoneId.systemDefault()
) {
  def active(context: RequestContext): Boolean = {
    val now = context.now
    begin.forall(i => i.isBefore(now)) &&
    end.forall(i => i.isAfter(now)) &&
    (hourPeriods.isEmpty || hourPeriods.exists(_.active(context, timezone))) &&
    days.forall(_.active(context, timezone))
  }
  def empty: Boolean = {
    begin.isEmpty && end.isEmpty && hourPeriods.isEmpty && days.isEmpty
  }
}

sealed trait ActivationRule                extends LegacyCompatibleCondition {
  override def active(context: RequestContext, featureId: String): Boolean
}
object All                                 extends ActivationRule            {
  override def active(context: RequestContext, featureId: String): Boolean = true
}
case class UserList(users: Set[String])    extends ActivationRule            {
  override def active(context: RequestContext, featureId: String): Boolean = users.contains(context.user)
}
case class UserPercentage(percentage: Int) extends ActivationRule            {
  override def active(context: RequestContext, featureId: String): Boolean =
    Feature.isPercentageFeatureActive(s"${featureId}-${context.user}", percentage)
}

sealed trait LegacyCompatibleCondition {
  def active(requestContext: RequestContext, featureId: String): Boolean
}
case class DateRangeActivationCondition(begin: Option[Instant] = None, end: Option[Instant] = None, timezone: ZoneId)
    extends LegacyCompatibleCondition  {
  def active(context: RequestContext, featureId: String): Boolean = {
    val now = context.now
    begin.forall(i => i.atZone(timezone).toInstant.isBefore(now)) && end.forall(i =>
      i.atZone(timezone).toInstant.isAfter(now)
    )
  }
}

case class ZonedHourPeriod(hourPeriod: HourPeriod, timezone: ZoneId) extends LegacyCompatibleCondition {
  def active(context: RequestContext, featureId: String): Boolean = {
    val zonedStart = LocalDateTime
      .of(LocalDate.now(), hourPeriod.startTime)
      .atZone(timezone)
      .toInstant

    val zonedEnd = LocalDateTime
      .of(LocalDate.now(), hourPeriod.endTime)
      .atZone(timezone)
      .toInstant

    zonedStart.isBefore(context.now) && zonedEnd.isAfter(context.now)
  }
}

case class HourPeriod(startTime: LocalTime, endTime: LocalTime) {
  def active(context: RequestContext, timezone: ZoneId): Boolean = {
    val zonedStart = LocalDateTime
      .of(LocalDate.now(), startTime)
      .atZone(timezone)
      .toInstant

    val zonedEnd = LocalDateTime
      .of(LocalDate.now(), endTime)
      .atZone(timezone)
      .toInstant

    zonedStart.isBefore(context.now) && zonedEnd.isAfter(context.now)
  }
}

case class ActivationDayOfWeeks(days: Set[DayOfWeek]) {
  def active(context: RequestContext, timezone: ZoneId): Boolean =
    days.contains(context.now.atZone(timezone).getDayOfWeek)
}

object FeaturePeriod {
  implicit val activationPeriodRead: Reads[FeaturePeriod] = json => {
    val maybeHourPeriod     = (json \ "hourPeriods").asOpt[Set[HourPeriod]].getOrElse(Set())
    val maybeActivationDays = (json \ "activationDays").asOpt[ActivationDayOfWeeks]
    val maybeBegin          = (json \ "begin").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    val maybeEnd            = (json \ "end").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    val maybeZone           = (json \ "timezone").asOpt[String].map(str => ZoneId.of(str))

    JsSuccess(
      FeaturePeriod(
        begin = maybeBegin,
        end = maybeEnd,
        hourPeriods = maybeHourPeriod,
        days = maybeActivationDays,
        timezone = maybeZone.getOrElse(ZoneId.systemDefault()) // TODO should this be allowed ?
      )
    )
  }

  implicit val featurePeriodeWrite: Writes[FeaturePeriod] = Writes[FeaturePeriod] { period =>
    if (period.empty) {
      JsNull
    } else {
      Json.obj(
        "begin"          -> period.begin,
        "end"            -> period.end,
        "hourPeriods"    -> period.hourPeriods,
        "activationDays" -> period.days,
        "timezone"       -> period.timezone
      )
    }
  }
}

object ActivationRule {
  implicit val activationRuleRead: Reads[ActivationRule] = json => {
    if (json.equals(Json.obj())) {
      JsSuccess(All)
    } else {
      (for (percentage <- (json \ "percentage").asOpt[Int]) yield UserPercentage(percentage = percentage))
        .orElse(
          for (users <- (json \ "users").asOpt[Seq[String]]) yield UserList(users = users.toSet)
        )
        .map(JsSuccess(_))
        .getOrElse(JsError("Invalid activation rule"))
    }
  }

  implicit val activationRuleWrite: Writes[ActivationRule] = Writes[ActivationRule] {
    case All                        =>
      Json.obj(
      )
    case UserList(users)            =>
      Json.obj(
        "users" -> users
      )
    case UserPercentage(percentage) =>
      Json.obj(
        "percentage" -> percentage
      )
  }
}

object ActivationCondition {
  val hourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
  def activationConditionReads: ResultType => Reads[ActivationCondition] = resultType => Reads[ActivationCondition] {json => {
    resultType match {
      case BooleanResult => booleanActivationConditionRead.reads(json)
      case StringResult => stringActivationConditionRead.reads(json)
      case NumberResult => numberActivationConditionRead.reads(json)
    }
  }}


  def booleanConditionWrites: Writes[BooleanActivationCondition] = Writes[BooleanActivationCondition] {
    cond => Json.obj(
      "period" -> cond.period,
      "rule"   -> cond.rule
    )
  }

  implicit def activationConditionWrite: Writes[ActivationCondition] = Writes[ActivationCondition] {
    case NumberActivationCondition(period, rule, value) =>
      Json.obj(
        "period" -> period,
        "rule"   -> rule,
        "value"  -> JsNumber(value)
      )
    case StringActivationCondition(period, rule, value) =>
      Json.obj(
        "period" -> period,
        "rule"   -> rule,
        "value"  -> value
      )
    case BooleanActivationCondition(period, rule)       =>
      Json.obj(
        "period" -> period,
        "rule"   -> rule
      )
  }

  def booleanActivationConditionRead: Reads[BooleanActivationCondition] = Reads[BooleanActivationCondition] { json =>
    {
      val maybeRule   = (json \ "rule").asOpt[ActivationRule];
      val maybePeriod = (json \ "period").asOpt[FeaturePeriod];
      if (maybeRule.isDefined || maybePeriod.isDefined) {
        JsSuccess(
          BooleanActivationCondition(
            rule = maybeRule.getOrElse(All),
            period = maybePeriod.getOrElse(FeaturePeriod())
          )
        )
      } else {
        JsError("Invalid activation condition")
      }
    }
  }

  def numberActivationConditionRead: Reads[NumberActivationCondition] = Reads[NumberActivationCondition] {
    json => {
      val maybeRule   = (json \ "rule").asOpt[ActivationRule];
      val maybePeriod = (json \ "period").asOpt[FeaturePeriod];

      (json \ "value").asOpt[BigDecimal]
        .map(bd => {
          if (maybeRule.isDefined || maybePeriod.isDefined) {
            JsSuccess(
              NumberActivationCondition(
                rule = maybeRule.getOrElse(All),
                period = maybePeriod.getOrElse(FeaturePeriod()),
                value = bd
              )
            )
          } else {
            JsError("Missing both rule and period for activation condition")
          }
        })
        .getOrElse(JsError("Missing/incorrect value for NumberActivationCondition"))
    }
  }

  def stringActivationConditionRead: Reads[StringActivationCondition] = Reads[StringActivationCondition] {
    json => {
      val maybeRule   = (json \ "rule").asOpt[ActivationRule];
      val maybePeriod = (json \ "period").asOpt[FeaturePeriod];

      (json \ "value").asOpt[String]
        .map(bd => {
          if (maybeRule.isDefined || maybePeriod.isDefined) {
            JsSuccess(
              StringActivationCondition(
                rule = maybeRule.getOrElse(All),
                period = maybePeriod.getOrElse(FeaturePeriod()),
                value = bd
              )
            )
          } else {
            JsError("Missing both rule and period for activation condition")
          }
        })
        .getOrElse(JsError("Missing/incorrect value for StringActivationCondition"))
    }
  }

  def valuedActivationConditionRead: ValuedResultType => Reads[ValuedActivationCondition] = resultType =>
    Reads[ValuedActivationCondition] { json =>
      resultType match {
        case StringResult => stringActivationConditionRead.reads(json)
        case NumberResult => numberActivationConditionRead.reads(json)
      }
    }
}

object OffsetTimeUtils {
  implicit val offsetTimeWrites: Writes[OffsetTime] = Writes[OffsetTime] { time =>
    Json.toJson(time.format(DateTimeFormatter.ISO_OFFSET_TIME))
  }

  implicit val offsetTimeReads: Reads[OffsetTime] = Reads[OffsetTime] { json =>
    try {
      JsSuccess(OffsetTime.parse(json.as[String], DateTimeFormatter.ISO_OFFSET_TIME))
    } catch {
      case e: DateTimeParseException => JsError("Invalid time format")
    }
  }
}

object LegacyCompatibleCondition {
  implicit val legacyCompatibleConditionWrites: Writes[LegacyCompatibleCondition] = {
    case DateRangeActivationCondition(begin, end, timezone) => {
      Json
        .obj(
          "timezone" -> timezone
        )
        .applyOnWithOpt(begin) { (json, begin) => json ++ Json.obj("begin" -> begin) }
        .applyOnWithOpt(end) { (json, end) => json ++ Json.obj("end" -> end) }
    }
    case ZonedHourPeriod(hourPeriod, timezone)              =>
      HourPeriod.hourPeriodWrites.writes(hourPeriod).as[JsObject] ++ Json.obj("timezone" -> timezone)
    case All                                                => Json.obj()
    case u: UserList                                        => ActivationRule.activationRuleWrite.writes(u)
    case u: UserPercentage                                  => ActivationRule.activationRuleWrite.writes(u)
  }

  implicit val legacyActivationConditionRead: Reads[LegacyCompatibleCondition] = json => {
    (json \ "percentage")
      .asOpt[Int]
      .map(p => UserPercentage(p))
      .orElse { (json \ "users").asOpt[Seq[String]].map(s => UserList(s.toSet)) }
      .orElse {
        val from = (json \ "begin").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        val to   = (json \ "end").asOpt[Instant](instantReads(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

        (json \ "timezone")
          .asOpt[ZoneId]
          .flatMap(zone => {
            (from, to) match {
              case (f @ Some(_), t @ Some(_)) => Some(DateRangeActivationCondition(begin = f, end = t, timezone = zone))
              case (f @ Some(_), None)        => Some(DateRangeActivationCondition(begin = f, end = None, timezone = zone))
              case (None, t @ Some(_))        => Some(DateRangeActivationCondition(begin = None, end = t, timezone = zone))
              case _                          => None
            }
          })
      }
      .orElse {
        for (
          from     <- (json \ "startTime").asOpt[LocalTime];
          to       <- (json \ "endTime").asOpt[LocalTime];
          timezone <- (json \ "timezone").asOpt[ZoneId]
        ) yield ZonedHourPeriod(HourPeriod(startTime = from, endTime = to), timezone)
      }
      .map(cond => JsSuccess(cond))
      .getOrElse(
        if (json.asOpt[JsObject].exists(obj => obj.value.isEmpty)) JsSuccess(All)
        else JsError("Failed to read condition")
      )
  }
}

object ActivationDayOfWeeks {
  implicit val activationDayOfWeekWrites: Writes[ActivationDayOfWeeks] = Writes[ActivationDayOfWeeks] { a =>
    Json.obj(
      "days" -> a.days
    )
  }

  implicit val activationDayOfWeekReads: Reads[ActivationDayOfWeeks] = Reads[ActivationDayOfWeeks] { json =>
    (for (days <- (json \ "days").asOpt[Set[DayOfWeek]]) yield JsSuccess(ActivationDayOfWeeks(days = days)))
      .getOrElse(JsError("Failed to parse day of week period"))
  }

  implicit val dayOfWeekWrites: Writes[DayOfWeek] = Writes[DayOfWeek] { d =>
    Json.toJson(d.name)
  }

  implicit val dayOfWeekReads: Reads[DayOfWeek] = Reads[DayOfWeek] { json =>
    json.asOpt[String].map(DayOfWeek.valueOf).map(JsSuccess(_)).getOrElse(JsError(s"Incorrect day of week : ${json}"))
  }
}

object HourPeriod {
  implicit val hourPeriodWrites: Writes[HourPeriod] = Writes[HourPeriod] { p =>
    Json.obj(
      "startTime" -> p.startTime.format(hourFormatter),
      "endTime"   -> p.endTime.format(hourFormatter)
    )
  }

  implicit val hourPeriodReads: Reads[HourPeriod] = Reads[HourPeriod] { json =>
    (for (
      start <- (json \ "startTime").asOpt[LocalTime];
      end   <- (json \ "endTime").asOpt[LocalTime]
    )
      yield JsSuccess(
        HourPeriod(
          startTime = start,
          endTime = end
        )
      )).getOrElse(JsError("Failed to parse hour period"))

  }

}
