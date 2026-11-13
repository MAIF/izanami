package fr.maif.izanami.services

import fr.maif.izanami.models.features.*
import fr.maif.izanami.models.{Feature, RequestContext}
import play.api.Logger

class EvaluationService {

}

object EvaluationService {
  private val logger = Logger("flag-evaluation")
  def evaluate(resultDescriptor: ResultDescriptor, context: RequestContext, feature: String): EvaluationResultWrapper = {
    resultDescriptor match {
      case descriptor: StringResultDescriptor => StringResultWrapper(evaluateString(descriptor, context = context, feature = feature))
      case descriptor: NumberResultDescriptor => NumberResultWrapper(evaluateNumber(descriptor, context = context, feature = feature))
      case descriptor: BooleanResultDescriptor => BooleanResultWrapper(evaluateBoolean(descriptor, context = context, feature = feature))
    }
  }

  def evaluateString(resultDescriptor: StringResultDescriptor, context: RequestContext, feature: String): String = {
    val ruleAndPeriods = toEvaluableActivationCondition(resultDescriptor.conditions.map(c => c.rule))
      .zip(resultDescriptor.conditions.map(c => (c.period, c.value)))
      .map((r, t) => (r, t._1, t._2))
    val matches = ruleAndPeriods.filter((r, p, _) => isFeaturePeriodActive(p, context = context) && isFeatureRuleActive(r, context = context, feature = feature))
    if (matches.size > 1) {
      logger.error(s"Several matching value found for flag $feature, will return first one")
      matches.head._3
    } else if (matches.size == 1) {
      matches.head._3
    } else {
      resultDescriptor.value
    }
  }

  def evaluateNumber(resultDescriptor: NumberResultDescriptor, context: RequestContext, feature: String): BigDecimal = {
    val ruleAndPeriods = toEvaluableActivationCondition(resultDescriptor.conditions.map(c => c.rule))
      .zip(resultDescriptor.conditions.map(c => (c.period, c.value)))
      .map((r, t) => (r, t._1, t._2))
    val matches = ruleAndPeriods.filter((r, p, _) => isFeaturePeriodActive(p, context = context) && isFeatureRuleActive(r, context = context, feature = feature))
    if (matches.size > 1) {
      logger.error(s"Several matching value found for flag $feature, will return first one")
      matches.head._3
    } else if (matches.size == 1) {
      matches.head._3
    } else {
      resultDescriptor.value
    }
  }

  def evaluateBoolean(resultDescriptor: BooleanResultDescriptor, context: RequestContext, feature: String): Boolean = {
    val ruleAndPeriods = toEvaluableActivationCondition(resultDescriptor.conditions.map(c => c.rule)).zip(resultDescriptor.conditions.map(_.period))
    ruleAndPeriods.isEmpty || ruleAndPeriods.exists((r, p) => isFeaturePeriodActive(p, context = context) && isFeatureRuleActive(r, context = context, feature = feature))
  }

  private def toEvaluableActivationCondition(conditions: Seq[ActivationRule]): Seq[EvaluableActivationRule] = {
    var currentRangeStart = 0
    conditions.map {
      case UserPercentage(percentage) => {
        val r = RangeBasedUserPercentage(percentage = percentage, intervalStart = currentRangeStart)
        currentRangeStart = currentRangeStart + percentage
        r
      }
      case All => All
      case l: UserList => l
    }
  }

  private def isFeatureRuleActive(rule: EvaluableActivationRule, context: RequestContext, feature: String): Boolean = {
    rule match {
      case All => true
      case UserList(users, groups) => users.contains(context.user) || groups.exists(context.groups.contains)
      case RangeBasedUserPercentage(percentage, intervalStart) => Feature.isPercentageFeatureActive(
        s"${feature}-${context.user}",
        percentage,
        intervalStart = intervalStart
      )
    }
  }

  private def isFeaturePeriodActive(period: FeaturePeriod, context: RequestContext): Boolean = {
    val now = context.now
    period.begin.forall(i => i.isBefore(now)) &&
      period.end.forall(i => i.isAfter(now)) &&
      (period.hourPeriods.isEmpty || period.hourPeriods.exists(_.active(context, period.timezone))) &&
      period.days.forall(_.active(context, period.timezone))
  }
}
