package fr.maif.izanami.units

import fr.maif.izanami.models.{ProjectAtomicRight, ProjectRightLevel, RightLevel, Rights, TenantRight}
import fr.maif.izanami.models.RightLevel.{Admin, Read, Write}
import fr.maif.izanami.services.{CompleteRights, RightService}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.matchers.must.Matchers.mustBe

class RightServiceTest extends AnyWordSpec with Matchers {
  "effectiveRights" should {
    "keep only higher right" in {
      val rightByRoles = Map(
        "dev"   -> CompleteRights(
          tenants = Map(
            "tenant" -> TenantRight(level = RightLevel.Read, Map("p1" -> ProjectAtomicRight(ProjectRightLevel.Read)))
          ),
          admin = false
        ),
        "ops"   -> CompleteRights(
          tenants = Map(
            "tenant" -> TenantRight(
              level = RightLevel.Write,
              Map("p1" -> ProjectAtomicRight(ProjectRightLevel.Write))
            )
          ),
          admin = false
        ),
        "admin" -> CompleteRights(
          tenants = Map(
            "tenant" -> TenantRight(
              level = RightLevel.Admin,
              projects = Map("p1" -> ProjectAtomicRight(ProjectRightLevel.Admin))
            )
          ),
          admin = true
        )
      )

      val result = RightService.effectiveRights(rightByRoles, Set("dev", "ops"))
      result.admin mustBe false
      result.tenants("tenant").level mustBe Write
      result.tenants("tenant").projects("p1").level mustBe ProjectRightLevel.Write

      val result2 = RightService.effectiveRights(rightByRoles, Set("dev", "admin"))
      result2.admin mustBe true
      result2.tenants("tenant").level mustBe Admin
      result2.tenants("tenant").projects("p1").level mustBe ProjectRightLevel.Admin
    }
  }
}
