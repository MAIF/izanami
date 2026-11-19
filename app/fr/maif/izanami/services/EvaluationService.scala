package fr.maif.izanami.services

import fr.maif.izanami.datastores.FeaturesDatastore
import fr.maif.izanami.models.*
import fr.maif.izanami.models.features.*
import fr.maif.izanami.services.EvaluationService.{
  isFeatureRuleActive,
  isLegacyConditionActive,
  logger
}
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.BetterFutureEither
import fr.maif.izanami.wasm.{WasmRelatedStuff, WasmUtils}
import play.api.Logger

import java.time.ZoneId
import scala.concurrent.ExecutionContext

class EvaluationService(
    wasm: WasmRelatedStuff,
    datastore: FeaturesDatastore
)(implicit val ec: ExecutionContext) {
  def evaluate(
      features: Seq[FeatureStrategies],
      ctx: RequestContext
  ): FutureEither[Seq[EvaluatedCompleteFeature]] = {
    features.foldLeft(
      FutureEither.success(Seq()): FutureEither[Seq[EvaluatedCompleteFeature]]
    )((acc, next) => {
      for (
        a <- acc;
        res <- this.evaluate(featureStrategies = next, ctx = ctx)
      ) yield a.appended(EvaluatedCompleteFeature(
        featureStrategies = next,
        result = res
      ))
    })
  }

  def evaluate(
      strategies: Map[String, LightWeightFeature],
      ctx: RequestContext
  ): FutureEither[EvaluationResultWrapper] = {
    val strategyToUse =
      EvaluationService.findStrategyForContext(strategies, ctx)
    datastore.readCompleteFeature(tenant = ctx.tenant, strategyToUse)
      .flatMap(strategyToUse =>
        evaluate(feature = strategyToUse, requestContext = ctx)
      )
  }

  def evaluate(
      featureStrategies: FeatureStrategies,
      ctx: RequestContext
  ): FutureEither[EvaluationResultWrapper] = {
    val strategyToUse = EvaluationService.findStrategyForContext(
      featureStrategies.strategies,
      ctx
    )
    evaluate(feature = strategyToUse, requestContext = ctx)
  }

  def evaluate(
      feature: CompleteFeature,
      requestContext: RequestContext
  ): FutureEither[EvaluationResultWrapper] = {
    feature match {
      case SingleConditionFeature(
            id,
            name,
            project,
            condition,
            enabled,
            tags,
            metadata,
            description
          ) => {
        val res = if (enabled) {
          isLegacyConditionActive(
            condition = condition,
            context = requestContext,
            feature = name
          )
        } else {
          false
        }
        FutureEither.success(BooleanResultWrapper(res))
      }
      case f: Feature => {
        val result = (f.enabled, f.resultDescriptor) match {
          case (false, r: BooleanResultDescriptor) =>
            BooleanResultWrapper(false)
          case (false, _) => NullResultWrapper
          case (true, rd) => EvaluationService.evaluate(
              rd,
              context = requestContext,
              feature = f.name
            )
        }
        FutureEither.success(result)
      }
      case f: CompleteWasmFeature => {
        (wasm.isWasmAllowed, f.enabled, f.resultType) match {
          case (false, true, BooleanResult) => {
            logger.warn(
              s"Evaluation result of wasm feature ${f.name} (id ${f.id}, context ${requestContext.context.toUserPath}) was changed to false, since this izanami instance doesn't allow wasm features"
            )
            FutureEither.success(BooleanResultWrapper(false))
          }
          case (false, true, _) => {
            logger.warn(
              s"Evaluation result of wasm feature ${f.name} (id ${f.id}, context ${requestContext.context.toUserPath}) was changed to null, since this izanami instance doesn't allow wasm features"
            )
            FutureEither.success(NullResultWrapper)
          }
          case (_, false, BooleanResult) =>
            FutureEither.success(BooleanResultWrapper(false))
          case (_, false, _) => FutureEither.success(NullResultWrapper)
          case (_, true, _)  => {
            val result = WasmUtils.handle(
              f.wasmConfig,
              requestContext,
              f.resultType,
              wasm.wasmIntegration
            )(ec)

            result.toFEither
          }
        }
      }
    }
  }
}

object EvaluationService {
  private val logger = Logger("flag-evaluation")
  def findStrategyForContext[T](
      strategies: Map[String, T],
      ctx: RequestContext
  ): T = {
    val context = ctx.contextAsString
    if (context.isBlank) {
      strategies("")
    } else {
      strategies
        .filter { case (ctx, f) => context.startsWith(ctx) }
        .toSeq
        .sortWith {
          case ((c1, _), (c2, _)) if c1.length < c2.length => false
          case _                                           => true
        }
        .headOption
        .map(_._2)
        .getOrElse(strategies(""))
    }
  }

  def evaluate(
      resultDescriptor: ResultDescriptor,
      context: RequestContext,
      feature: String
  ): EvaluationResultWrapper = {
    resultDescriptor match {
      case descriptor: StringResultDescriptor =>
        StringResultWrapper(evaluateString(
          descriptor,
          context = context,
          feature = feature
        ))
      case descriptor: NumberResultDescriptor =>
        NumberResultWrapper(evaluateNumber(
          descriptor,
          context = context,
          feature = feature
        ))
      case descriptor: BooleanResultDescriptor =>
        BooleanResultWrapper(evaluateBoolean(
          descriptor,
          context = context,
          feature = feature
        ))
    }
  }

  private def evaluateString(
      resultDescriptor: StringResultDescriptor,
      context: RequestContext,
      feature: String
  ): String = {
    val ruleAndPeriods =
      toEvaluableActivationCondition(
        resultDescriptor.conditions.map(c =>
          c.rule
        ),
        compoundRange = false
      )
        .zip(resultDescriptor.conditions.map(c => (c.period, c.value)))
        .map((r, t) => (r, t._1, t._2))
    val matches = ruleAndPeriods.filter((r, p, _) =>
      isFeaturePeriodActive(
        p,
        context = context
      ) && isFeatureRuleActive(r, context = context, feature = feature)
    )
    if (matches.nonEmpty) {
      matches.head._3
    } else {
      resultDescriptor.value
    }
  }

  private def evaluateNumber(
      resultDescriptor: NumberResultDescriptor,
      context: RequestContext,
      feature: String
  ): BigDecimal = {
    val ruleAndPeriods =
      toEvaluableActivationCondition(
        resultDescriptor.conditions.map(c =>
          c.rule
        ),
        compoundRange = false
      )
        .zip(resultDescriptor.conditions.map(c => (c.period, c.value)))
        .map((r, t) => (r, t._1, t._2))
    val matches = ruleAndPeriods.filter((r, p, _) =>
      isFeaturePeriodActive(
        p,
        context = context
      ) && isFeatureRuleActive(r, context = context, feature = feature)
    )
    if (matches.nonEmpty) {
      matches.head._3
    } else {
      resultDescriptor.value
    }
  }

  def evaluateBoolean(
      resultDescriptor: BooleanResultDescriptor,
      context: RequestContext,
      feature: String
  ): Boolean = {
    val ruleAndPeriods =
      toEvaluableActivationCondition(
        resultDescriptor.conditions.map(c =>
          c.rule
        ),
        compoundRange = true
      ).zip(resultDescriptor.conditions.map(_.period))
    ruleAndPeriods.isEmpty || ruleAndPeriods.exists((r, p) =>
      isFeaturePeriodActive(
        p,
        context = context
      ) && isFeatureRuleActive(r, context = context, feature = feature)
    )
  }

  def isLegacyConditionActive(
      condition: LegacyCompatibleCondition,
      context: RequestContext,
      feature: String
  ): Boolean = {
    condition match {
      case All                         => true
      case r @ UserList(users, groups) =>
        isFeatureRuleActive(r, context, feature)
      case UserPercentage(percentage) => isFeatureRuleActive(
          RangeBasedUserPercentage(percentage = percentage, intervalStart = 0),
          context,
          feature
        )
      case DateRangeActivationCondition(begin, end, timezone) => {
        val now = context.now
        begin.forall(i =>
          i.atZone(timezone).toInstant.isBefore(now)
        ) && end.forall(i =>
          i.atZone(timezone).toInstant.isAfter(now)
        )
      }
      case ZonedHourPeriod(hourPeriod, timezone) => {
        isHourPeriodActive(hourPeriod, context, timezone)
      }
    }
  }

  def toEvaluableActivationCondition(
      conditions: Seq[ActivationRule],
      compoundRange: Boolean
  )
      : Seq[EvaluableActivationRule] = {
    var currentRangeStart = 0
    conditions.map {
      case UserPercentage(percentage) => {
        val r = RangeBasedUserPercentage(
          percentage = percentage,
          intervalStart = currentRangeStart
        )
        if (!compoundRange) {
          currentRangeStart = currentRangeStart + percentage
        }
        r
      }
      case All         => All
      case l: UserList => l
    }
  }

  def isFeatureRuleActive(
      rule: EvaluableActivationRule,
      context: RequestContext,
      feature: String
  ): Boolean = {
    rule match {
      case All                     => true
      case UserList(users, groups) =>
        users.contains(context.user) || groups.exists(context.groups.contains)
      case RangeBasedUserPercentage(percentage, intervalStart) =>
        Feature.isPercentageFeatureActive(
          s"${feature}-${context.user}",
          percentage,
          intervalStart = intervalStart
        )
    }
  }

  private def isFeaturePeriodActive(
      period: FeaturePeriod,
      context: RequestContext
  ): Boolean = {
    val now = context.now
    period.begin.forall(i => i.isBefore(now)) &&
    period.end.forall(i => i.isAfter(now)) &&
    (period.hourPeriods.isEmpty || period.hourPeriods.exists(hours =>
      isHourPeriodActive(hours, context, period.timezone)
    )) &&
    period.days.forall(
      _.days.contains(now.atZone(period.timezone).getDayOfWeek)
    )
  }

  private def isHourPeriodActive(
      period: HourPeriod,
      context: RequestContext,
      timezone: ZoneId
  ): Boolean = {
    val now = context.now
    val date = now.atZone(timezone).toLocalDate
    val zonedStart = date.atTime(period.startTime).atZone(timezone).toInstant
    val zonedEnd = date.atTime(period.endTime).atZone(timezone).toInstant

    zonedStart.isBefore(now) && zonedEnd.isAfter(now)
  }
}
