package fr.maif.izanami.models.features

import fr.maif.izanami.models.{Feature, RequestContext}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.*

class ActivationConditionSpec extends PlaySpec {
  "Group targeting" should {
    "match users OR groups without mixing their namespaces" in {
      val rule = UserList(Set("alice"), Set("beta"))
      val context = RequestContext("tenant", "unknown")

      rule.active(context.copy(user = "alice"), "flag") mustBe true
      rule.active(context.copy(groups = Set("other", "beta")), "flag") mustBe true
      rule.active(context.copy(user = "beta"), "flag") mustBe false
      rule.active(context.copy(groups = Set("alice")), "flag") mustBe false
      rule.active(context, "flag") mustBe false
    }

    "preserve exact groups and the existing user identity" in {
      val rule = UserList(Set.empty, Set("beta,support", "", " spaced "))
      val context = RequestContext("tenant", "alice")
      rule.active(context.copy(groups = Set("beta")), "flag") mustBe false
      rule.active(context.copy(groups = Set("support")), "flag") mustBe false
      rule.active(context.copy(groups = Set("beta,support")), "flag") mustBe true
      rule.active(context.copy(groups = Set("")), "flag") mustBe true
      rule.active(context.copy(groups = Set("spaced")), "flag") mustBe false
      rule.active(context.copy(groups = Set(" spaced ")), "flag") mustBe true
      UserList(Set("")).active(context.copy(user = ""), "flag") mustBe true

      for (percentage <- Seq(0, 10, 50, 100)) {
        UserPercentage(percentage).active(context, "flag") mustBe
          UserPercentage(percentage).active(context.copy(groups = Set("beta")), "flag")
      }
      context.copy(groups = Set("beta")).wasmJson mustBe context.wasmJson
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

    "apply groups to every result type without bypassing periods" in {
      val rule = UserList(Set.empty, Set("beta"))
      val context = RequestContext("tenant", "alice", groups = Set("beta"))
      val conditions = Seq(
        BooleanActivationCondition(rule = rule),
        NumberActivationCondition(rule = rule, value = BigDecimal(42)),
        StringActivationCondition(rule = rule, value = "beta-value")
      )
      for (condition <- conditions) {
        condition.active(context, "flag") mustBe true
        condition.active(context.copy(groups = Set.empty), "flag") mustBe false
      }
      BooleanActivationCondition(
        period = FeaturePeriod(end = Some(context.now.minusSeconds(1))),
        rule = rule
      ).active(context, "flag") mustBe false
    }
  }
}
