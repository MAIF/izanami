package fr.maif.izanami.units

import fr.maif.izanami.models.*
import fr.maif.izanami.models.RightLevel.Admin
import fr.maif.izanami.models.RightLevel.Read
import fr.maif.izanami.models.RightLevel.Write
import fr.maif.izanami.services.CompleteRights
import fr.maif.izanami.services.MaxRights
import fr.maif.izanami.services.MaxTenantRoleRights
import fr.maif.izanami.services.RightService
import org.scalatest.matchers.must.Matchers.mustBe
import org.scalatest.matchers.must.Matchers.mustEqual
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RightServiceTest extends AnyWordSpec with Matchers {
  "effectiveRights" should {
    "keep only higher right" in {
      val rightByRoles = Map(
        "dev" -> CompleteRights(
          tenants = Map(
            "tenant" -> TenantRight(
              level = RightLevel.Read,
              Map("p1" -> ProjectAtomicRight(ProjectRightLevel.Read))
            )
          ),
          admin = false
        ),
        "ops" -> CompleteRights(
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
              projects =
                Map("p1" -> ProjectAtomicRight(ProjectRightLevel.Admin))
            )
          ),
          admin = true
        )
      )

      val result = RightService.effectiveRights(rightByRoles, Set("dev", "ops"))
      result.admin mustBe false
      result.tenants("tenant").level mustBe Write
      result
        .tenants("tenant")
        .projects("p1")
        .level mustBe ProjectRightLevel.Write

      val result2 =
        RightService.effectiveRights(rightByRoles, Set("dev", "admin"))
      result2.admin mustBe true
      result2.tenants("tenant").level mustBe Admin
      result2
        .tenants("tenant")
        .projects("p1")
        .level mustBe ProjectRightLevel.Admin
    }
  }

  "maxRightsToApply" should {
    "take default rights into account" in {
      val maxRights = Map(
        "" -> MaxRights(
          admin = false,
          tenants = Map(
            "secret" -> MaxTenantRoleRights(
              level = Read,
              maxProjectRight = ProjectRightLevel.Update,
              maxKeyRight = Read,
              maxWebhookRight = Read
            )
          )
        ),
        "user" -> MaxRights(
          admin = false,
          tenants = Map(
            "secret" -> MaxTenantRoleRights(
              level = Read,
              maxProjectRight = ProjectRightLevel.Read,
              maxKeyRight = Read,
              maxWebhookRight = Read
            )
          )
        )
      )

      val res = CompleteRights.maxRightsToApply(Set("user"), maxRights)
      val tenantRight = res.tenants("secret")

      tenantRight.maxProjectRight shouldBe ProjectRightLevel.Update
    }
  }

  "max maxRights" should {
    "remove tenant data if present on one side and absent on the other" in {
      val res = CompleteRights.max(
        MaxRights(
          admin = false,
          tenants = Map(
            "secret" -> MaxTenantRoleRights(
              level = Read,
              maxProjectRight = ProjectRightLevel.Read,
              maxKeyRight = Read,
              maxWebhookRight = Read
            )
          )
        ),
        MaxRights(admin = false, tenants = Map())
      )
      res.tenants.get("secret") mustBe None

    }

    "keep higher level if max right is present on both sides" in {
      val res = CompleteRights.max(
        MaxRights(
          admin = false,
          tenants = Map(
            "secret" -> MaxTenantRoleRights(
              level = Write,
              maxProjectRight = ProjectRightLevel.Read,
              maxKeyRight = Read,
              maxWebhookRight = Write
            )
          )
        ),
        MaxRights(
          admin = true,
          tenants = Map(
            "secret" -> MaxTenantRoleRights(
              level = Read,
              maxProjectRight = ProjectRightLevel.Update,
              maxKeyRight = Admin,
              maxWebhookRight = Read
            )
          )
        )
      )

      res.admin mustBe true
      val secretRights = res.tenants("secret")
      secretRights.maxKeyRight mustBe Admin
      secretRights.level mustBe Write
      secretRights.maxProjectRight mustBe ProjectRightLevel.Update
      secretRights.maxWebhookRight mustBe Write

    }
  }

  "checkCompliance" should {
    "pass if tenant is absent from provided max rights" in {
      val rights = CompleteRights(
        admin = false,
        tenants = Map(
          "secret" -> TenantRight(
            level = Read,
            projects =
              Map("proj" -> ProjectAtomicRight(ProjectRightLevel.Admin)),
            keys = Map("key" -> GeneralAtomicRight(Admin)),
            webhooks = Map("wh" -> GeneralAtomicRight(Admin)),
            defaultProjectRight = ProjectRightLevel.Admin,
            defaultKeyRight = Admin,
            defaultWebhookRight = Admin
          )
        )
      )

      val compliance =
        rights.checkCompliance(MaxRights(admin = false, tenants = Map()))

      compliance.isEmpty mustBe true
    }

    "raise compliance issues if any" in {
      val rights = CompleteRights(
        admin = true,
        tenants = Map(
          "secret" -> TenantRight(
            level = Write,
            projects =
              Map("proj" -> ProjectAtomicRight(ProjectRightLevel.Write)),
            keys = Map("key" -> GeneralAtomicRight(Admin)),
            webhooks = Map("wh" -> GeneralAtomicRight(Admin)),
            defaultProjectRight = ProjectRightLevel.Update,
            defaultKeyRight = Write,
            defaultWebhookRight = Write
          )
        )
      )

      val compliance = rights.checkCompliance(MaxRights(
        admin = false,
        tenants = Map(
          "secret" -> MaxTenantRoleRights(
            level = Read,
            maxProjectRight = ProjectRightLevel.Read,
            maxKeyRight = Read,
            maxWebhookRight = Read
          )
        )
      ))

      compliance.isEmpty mustBe false
      compliance.admin mustBe true
      val secretComplianceResult = compliance.tenants("secret")

      secretComplianceResult.levelRight.get.before mustBe Write
      secretComplianceResult.levelRight.get.after mustBe Read

      secretComplianceResult.projects(
        "proj"
      ).before mustBe ProjectRightLevel.Write
      secretComplianceResult.projects(
        "proj"
      ).after mustBe ProjectRightLevel.Read

      secretComplianceResult.keys("key").before mustBe Admin
      secretComplianceResult.keys("key").after mustBe Read

      secretComplianceResult.webhooks("wh").before mustBe Admin
      secretComplianceResult.webhooks("wh").after mustBe Read

      secretComplianceResult.defaultProjectRight.get.before mustBe ProjectRightLevel.Update
      secretComplianceResult.defaultProjectRight.get.after mustBe ProjectRightLevel.Read

      secretComplianceResult.defaultKeyRight.get.before mustBe Write
      secretComplianceResult.defaultKeyRight.get.after mustBe Read

      secretComplianceResult.defaultWebhookRight.get.before mustBe Write
      secretComplianceResult.defaultWebhookRight.get.after mustBe Read
    }
  }

  "updateToComplyWith" should {

    "update rights according to compliance result" in {
      val rights = CompleteRights(
        admin = true,
        tenants = Map(
          "secret" -> TenantRight(
            level = Write,
            projects =
              Map("proj" -> ProjectAtomicRight(ProjectRightLevel.Write)),
            keys = Map("key" -> GeneralAtomicRight(Admin)),
            webhooks = Map("wh" -> GeneralAtomicRight(Admin)),
            defaultProjectRight = ProjectRightLevel.Update,
            defaultKeyRight = Write,
            defaultWebhookRight = Write
          )
        )
      )

      val compliance = rights.checkCompliance(MaxRights(
        admin = false,
        tenants = Map(
          "secret" -> MaxTenantRoleRights(
            level = Read,
            maxProjectRight = ProjectRightLevel.Read,
            maxKeyRight = Read,
            maxWebhookRight = Read
          )
        )
      ))

      val res = rights.updateToComplyWith(compliance)

      res.admin mustBe false
      val secretRights = res.tenants("secret")
      secretRights.level mustBe Read
      secretRights.defaultProjectRight mustBe ProjectRightLevel.Read
      secretRights.defaultKeyRight mustBe Read
      secretRights.defaultWebhookRight mustBe Read
      secretRights.projects("proj").level mustBe ProjectRightLevel.Read
      secretRights.keys("key").level mustBe Read
      secretRights.webhooks("wh").level mustBe Read
    }

    "do nothing if compliance result is empty" in {
      val rights = CompleteRights(
        admin = true,
        tenants = Map(
          "secret" -> TenantRight(
            level = Write,
            projects =
              Map("proj" -> ProjectAtomicRight(ProjectRightLevel.Write)),
            keys = Map("key" -> GeneralAtomicRight(Admin)),
            webhooks = Map("wh" -> GeneralAtomicRight(Admin)),
            defaultProjectRight = ProjectRightLevel.Update,
            defaultKeyRight = Write,
            defaultWebhookRight = Write
          )
        )
      )

      val compliance = rights.checkCompliance(MaxRights(
        admin = true,
        tenants = Map(
          "secret" -> MaxTenantRoleRights(
            level = Write,
            maxProjectRight = ProjectRightLevel.Write,
            maxKeyRight = Admin,
            maxWebhookRight = Admin
          )
        )
      ))

      val res = rights.updateToComplyWith(compliance)

      res mustEqual rights
    }
  }

  "MaxTenantRoleRights allowRightForXXX" should {
    "allow rights if equal to max rights" in {
      val maxRights = MaxTenantRoleRights(
        level = Read,
        maxProjectRight = ProjectRightLevel.Read,
        maxKeyRight = Read,
        maxWebhookRight = Read
      )

      maxRights.allowRightForProject(ProjectRightLevel.Read) mustBe true
      maxRights.allowRightForKey(Read) mustBe true
      maxRights.allowRightForWebhook(Read) mustBe true
    }

    "not allow rights if superior to max rights" in {
      val maxRights = MaxTenantRoleRights(
        level = Read,
        maxProjectRight = ProjectRightLevel.Read,
        maxKeyRight = Read,
        maxWebhookRight = Read
      )

      maxRights.allowRightForProject(ProjectRightLevel.Update) mustBe false
      maxRights.allowRightForKey(Write) mustBe false
      maxRights.allowRightForWebhook(Admin) mustBe false
    }
  }
}
