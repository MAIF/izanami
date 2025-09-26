package fr.maif.izanami.units

import fr.maif.izanami.v1.OldScripts.doesUseHttp
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.wordspec.AnyWordSpec

import scala.util.hashing.MurmurHash3

class ScriptTest extends AnyWordSpec{
  "doesUseHttp function" should {
    "detect http function call in simple case" in {
      val script =
        """
          |/**
          | * context:  a JSON object containing app specific value
          | *           to evaluate the state of the feature
          | * enabled:  a callback to mark the feature as active
          | *           for this request
          | * disabled: a callback to mark the feature as inactive
          | *           for this request
          | * http:     a http client
          | */
          |function enabled(context, enabled, disabled, http) {
          |  http("foo");
          |  if (context.id === 'john.doe@gmail.com') {
          |    return enabled();
          |  }
          |  return disabled();
          |}
          |""".stripMargin

      doesUseHttp(script) mustBe true
    }

    "detect absence of http function call in simple case" in {
      val script =
        """
          |/**
          | * context:  a JSON object containing app specific value
          | *           to evaluate the state of the feature
          | * enabled:  a callback to mark the feature as active
          | *           for this request
          | * disabled: a callback to mark the feature as inactive
          | *           for this request
          | * http:     a http client
          | */
          |function enabled(context, enabled, disabled, http) {
          |  if (context.id === 'john.doe@gmail.com') {
          |    return enabled();
          |  }
          |  return disabled();
          |}
          |""".stripMargin

      doesUseHttp(script) mustBe false
    }

    "detect http call in nested if" in {
      val script =
        """
          |function enabled(context, enabled, disabled, http) {
          |  if (context.id === 'john.doe@gmail.com') {
          |    http("foo");
          |    return enabled();
          |  }
          |  return disabled();
          |}
          |""".stripMargin

      doesUseHttp(script) mustBe true
    }

    "detect absence of http param declaration" in {
      val script =
        """
          |function enabled(context, enabled, disabled) {
          |  if (context.id === 'john.doe@gmail.com') {
          |    return enabled();
          |  }
          |  return disabled();
          |}
          |""".stripMargin

      doesUseHttp(script) mustBe false
    }

    "detect usage of http as parameter" in {
      val script =
        """
          |function foo(smthg) {}
          |
          |function enabled(foo, bar, baz, http) {
          |  foo(http);
          |}
          |""".stripMargin

      doesUseHttp(script) mustBe true
    }

    "detect usage of http as another variable assignation" in {
      val script =
        """
          |function enabled(foo, bar, baz, http) {
          |  var bar = http;
          |}
          |""".stripMargin

      doesUseHttp(script) mustBe true
    }
  }
}
