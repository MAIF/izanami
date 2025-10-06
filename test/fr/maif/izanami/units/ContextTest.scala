package fr.maif.izanami.units

import fr.maif.izanami.services.FeatureService
import fr.maif.izanami.web.FeatureContextPath
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ContextTest extends AnyWordSpec with Matchers {
  "impactedProtectedContextsByRootUpdate" should {
    "return contexts without overload" in {
      val res = FeatureService.impactedProtectedContextsByRootUpdate(
        protectedContexts = Set(FeatureContextPath(Seq("prod")), FeatureContextPath(Seq("bar"))),
        currentOverloads = Set(FeatureContextPath(Seq("prod")))
      )

      res should contain theSameElementsAs Seq(FeatureContextPath(Seq("bar")))
    }

    "not return context that have overloaded parent context" in {
      val res = FeatureService.impactedProtectedContextsByRootUpdate(
        protectedContexts = Set(FeatureContextPath.fromUserString("prod"), FeatureContextPath.fromUserString("prod/mobile")),
        currentOverloads = Set(FeatureContextPath(Seq("prod")))
      )

      res shouldBe empty
    }

    "return contexts that are parent context of overloaded contexts" in {
      val res = FeatureService.impactedProtectedContextsByRootUpdate(
        protectedContexts = Set(FeatureContextPath.fromUserString("prod"), FeatureContextPath.fromUserString("prod/mobile")),
        currentOverloads = Set(FeatureContextPath.fromUserString("prod/mobile"))
      )

      res should contain theSameElementsAs Seq(FeatureContextPath(Seq("prod")))
    }
  }
}
