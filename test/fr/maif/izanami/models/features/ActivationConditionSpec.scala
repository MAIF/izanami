package fr.maif.izanami.models.features

import fr.maif.izanami.models.{Feature, RequestContext}
import fr.maif.izanami.services.EvaluationService
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.*

import java.time.{Instant, LocalTime, ZoneId}

class ActivationConditionSpec extends PlaySpec {
  "Hourly conditions" should {
    "use the date in their timezone and preserve strict boundaries" in {
      val now = Instant.parse("2026-01-02T00:30:00Z")
      val scenarios = Seq(
        (ZoneId.of("America/Los_Angeles"), LocalTime.of(16, 0), LocalTime.of(17, 0)),
        (ZoneId.of("Pacific/Kiritimati"), LocalTime.of(14, 0), LocalTime.of(15, 0))
      )
      for ((timezone, start, end) <- scenarios) {
        val hours = HourPeriod(start, end)
        val period = FeaturePeriod(hourPeriods = Set(hours), timezone = timezone)
        val descriptor = StringResultDescriptor(
          value = "base",
          conditions = Seq(StringActivationCondition(period = period, value = "sample"))
        )
        val legacy = ZonedHourPeriod(hours, timezone)
        val instants = Seq(
          (now.minusSeconds(1800), false),
          (now, true),
          (now.plusSeconds(1800), false)
        )
        for ((instant, expected) <- instants) {
          val context = RequestContext("tenant", "user", now = instant)
          withClue(s"timezone $timezone, instant $instant: ") {
            EvaluationService.evaluate(
              descriptor,
              context,
              "flag"
            ) mustBe StringResultWrapper(if (expected) { "sample" } else { "base" })
            EvaluationService.isLegacyConditionActive(legacy, context, "flag") mustBe expected
          }
        }
      }
    }
  }

  "Percentage alternatives" should {
    "allocate disjoint user ranges according to each alternative's percentage" in {
      val descriptor = StringResultDescriptor(
        value = "base",
        conditions = Seq(
          StringActivationCondition(rule = UserPercentage(10), value = "A"),
          StringActivationCondition(rule = UserPercentage(20), value = "B")
        )
      )
      val users = Seq(
        ("user-62", 1, "A"),
        ("user-78", 10, "A"),
        ("user-116", 11, "B"),
        ("user-90", 20, "B"),
        ("user-83", 21, "B"),
        ("user-199", 30, "B"),
        ("user-85", 31, "base"),
        ("user-49", 100, "base"),
        ("\uD83D\uDE80", 27, "B")
      )

      for ((user, bucket, expected) <- users) {
        withClue(s"user $user (bucket $bucket): ") {
          EvaluationService.evaluate(
            descriptor,
            RequestContext("tenant", user),
            "flag"
          ) mustBe StringResultWrapper(expected)
        }
      }
    }
  }

  "Boolean percentage conditions" should {
    "overlap user populations when a later condition becomes active" in {
      val start = Instant.parse("2026-01-02T00:00:00Z")
      val descriptor = BooleanResultDescriptor(Seq(
        BooleanActivationCondition(rule = UserPercentage(10)),
        BooleanActivationCondition(
          period = FeaturePeriod(begin = Some(start)),
          rule = UserPercentage(20)
        )
      ))
      val users = Seq(
        ("user-62", 1, true, true),
        ("user-78", 10, true, true),
        ("user-116", 11, false, true),
        ("user-90", 20, false, true),
        ("user-83", 21, false, false),
        ("user-199", 30, false, false),
        ("user-85", 31, false, false),
        ("user-49", 100, false, false)
      )

      for ((user, bucket, before, after) <- users) {
        val context = RequestContext("tenant", user)
        withClue(s"user $user (bucket $bucket), before the start: ") {
          EvaluationService.evaluate(
            descriptor,
            context.copy(now = start.minusSeconds(1)),
            "flag"
          ) mustBe BooleanResultWrapper(before)
        }
        withClue(s"user $user (bucket $bucket), after the start: ") {
          EvaluationService.evaluate(
            descriptor,
            context.copy(now = start.plusSeconds(1)),
            "flag"
          ) mustBe BooleanResultWrapper(after)
        }
      }
    }
  }

  "Group targeting" should {
    "match users OR groups without mixing their namespaces" in {
      val rule = UserList(Set("alice"), Set("beta"))
      val context = RequestContext("tenant", "unknown")

      EvaluationService.isFeatureRuleActive(rule, context.copy(user = "alice"), "flag") mustBe true
      EvaluationService.isFeatureRuleActive(rule, context.copy(groups = Set("other", "beta")), "flag") mustBe true
      EvaluationService.isFeatureRuleActive(rule, context.copy(user = "beta"), "flag") mustBe false
      EvaluationService.isFeatureRuleActive(rule, context.copy(groups = Set("alice")), "flag") mustBe false
      EvaluationService.isFeatureRuleActive(rule, context, "flag") mustBe false
    }

    "preserve exact groups and the existing user identity" in {
      val rule = UserList(Set.empty, Set("beta,support", "", " spaced "))
      val context = RequestContext("tenant", "alice")
      EvaluationService.isFeatureRuleActive(rule, context.copy(groups = Set("beta")), "flag") mustBe false
      EvaluationService.isFeatureRuleActive(rule, context.copy(groups = Set("support")), "flag") mustBe false
      EvaluationService.isFeatureRuleActive(rule, context.copy(groups = Set("beta,support")), "flag") mustBe true
      EvaluationService.isFeatureRuleActive(rule, context.copy(groups = Set("")), "flag") mustBe true
      EvaluationService.isFeatureRuleActive(rule, context.copy(groups = Set("spaced")), "flag") mustBe false
      EvaluationService.isFeatureRuleActive(rule, context.copy(groups = Set(" spaced ")), "flag") mustBe true
      EvaluationService.isFeatureRuleActive(UserList(Set("")), context.copy(user = ""), "flag") mustBe true
    }

    "round trip groups and keep old rules unchanged" in {
      val oldJson = Json.obj("users" -> Json.arr("alice"))
      val oldRule = oldJson.as[ActivationRule]
      Json.toJson(oldRule) mustBe oldJson
      oldRule mustBe UserList(Set("alice"))

      val groupOnly = Json.obj("groups" -> Json.arr("beta"))
      groupOnly.as[ActivationRule] mustBe UserList(Set.empty, Set("beta"))
      for (rule <- Seq(UserList(Set.empty, Set("beta")), UserList(Set("alice"), Set("beta")))) {
        Json.toJson[ActivationRule](rule).as[ActivationRule] mustBe rule
      }
      Json.obj().as[ActivationRule] mustBe All
    }

    "reject invalid groups even when a valid period is supplied" in {
      val period = Json.obj("activationDays" -> Json.obj("days" -> Json.arr("MONDAY")))
      val invalidRules = Seq(
        Json.obj("users" -> Json.arr(), "groups" -> Json.arr()),
        Json.obj("groups" -> Json.arr()),
        Json.obj("groups" -> "beta"),
        Json.obj("groups" -> JsNull),
        Json.obj("groups" -> Json.arr("beta"), "percentage" -> 50),
        Json.obj("users" -> Json.arr("alice"), "groups" -> Json.arr(42)),
        Json.obj("users" -> 42, "groups" -> Json.arr("beta"))
      )
      for (rule <- invalidRules) {
        rule.validate[ActivationRule].isError mustBe true
        val condition = Json.obj("rule" -> rule, "period" -> period, "value" -> 42)
        ActivationCondition.booleanActivationConditionRead.reads(condition).isError mustBe true
        ActivationCondition.numberActivationConditionRead.reads(condition).isError mustBe true
        ActivationCondition.stringActivationConditionRead
          .reads(condition ++ Json.obj("value" -> "enabled")).isError mustBe true
      }
      Feature.readCompleteFeature(Json.obj(
        "name" -> "flag", "enabled" -> true, "resultType" -> "boolean",
        "conditions" -> Json.obj("users" -> Json.arr("alice"), "groups" -> Json.arr("beta"))
      )).isError mustBe true
    }

    "apply groups without bypassing periods" in {
      val rule = UserList(Set.empty, Set("beta"))
      val context = RequestContext("tenant", "alice", groups = Set("beta"))

      val booleanCondition = BooleanActivationCondition(rule = rule)
      EvaluationService.evaluateBoolean(BooleanResultDescriptor(Seq(booleanCondition)), context = context, feature = "flag") mustBe true
      EvaluationService.evaluateBoolean(BooleanResultDescriptor(Seq(booleanCondition)), context = context.copy(groups = Set.empty), feature = "flag") mustBe false
      EvaluationService.evaluateBoolean(BooleanResultDescriptor(Seq(BooleanActivationCondition(period = FeaturePeriod(end = Some(context.now.minusSeconds(1))), rule = rule))), context = context, feature = "flag") mustBe false
    }
  }
}
