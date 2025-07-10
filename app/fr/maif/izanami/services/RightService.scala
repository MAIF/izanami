package fr.maif.izanami.services

import fr.maif.izanami.{RoleRights, TenantRoleRights}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.ProjectRightLevel.ProjectRightOrdering
import fr.maif.izanami.models.RightLevel.RightOrdering
import fr.maif.izanami.models.{GeneralAtomicRight, OAuth2Configuration, ProjectAtomicRight, ProjectRightLevel, Rights, TenantRight, User, UserRightsUpdateRequest}
import fr.maif.izanami.services.RightService.{RightsByRole, Role, effectiveRights, keepHigher}
import fr.maif.izanami.utils.syntax.implicits.BetterJsValue
import play.api.libs.json.{JsError, JsSuccess, Json, Reads, Writes}

import scala.concurrent.{ExecutionContext, Future}

class RightService(env: Env) {
  private implicit val executionContext: ExecutionContext = env.executionContext


  def generateRightsForRoles(roles: Set[Role]): Future[Option[CompleteRights]] = {
    maybeRightByRoles.map(o => o.map(rights => effectiveRights(rights, roles)))
  }

  def updateUserRightIfNeeded(user: String, roles: Set[Role]): Future[Either[IzanamiError, Unit]] = {
    env.datastores.users.findCompleteRights(user)
      .flatMap {
        case Some(userWithRights) => {
          val userRight = CompleteRights(rights = userWithRights.rights, admin = userWithRights.admin)
          maybeRightByRoles.map(o => o.map(rights => effectiveRights(rights, roles)).filter(cr => cr != userRight))
            .flatMap {
              case Some(rightToUpdate) => env.datastores.users.updateUserRights(userWithRights.username, UserRightsUpdateRequest.fromRights(rightToUpdate))
              case None => Future.successful(Right(()))
            }
        }
        case None => Future.successful(Right(()))
      }
  }

  def maybeRightByRoles: Future[Option[RightsByRole]] = {
    env.datastores.configuration.readFullConfiguration().map(_.toOption.flatMap(_.oidcConfiguration))
      .map(_.map(c => c.userRightsByRoles))
  }
}

case object RightService {
  type Role = String
  type Tenant = String
  type Project = String
  type RightsByRole = Map[Role, CompleteRights]

  def effectiveRights(defaultRights: RightsByRole, effectiveRoles: Set[Role]): CompleteRights = {
    defaultRights.collect {
        case (role, rights) if effectiveRoles.contains(role) => rights
      }
      .foldLeft(CompleteRights.EMPTY)((r1, r2) => r1.mergeWith(r2))
  }

  /*def keepHigher(r1: CompleteRights, r2: CompleteRights): Unit = {

  }*/

  def keepHigher(r1: TenantRight, r2: TenantRight): TenantRight = {
    val level = Seq(r1.level, r2.level).max(RightOrdering)
    val projectRights = r1.projects.toSeq.concat(r2.projects.toSeq).groupMapReduce(_._1)(_._2)((r1, r2) => Seq(r1, r2).maxBy(_.level)(ProjectRightOrdering))
    val keyRights = r1.keys.toSeq.concat(r2.keys.toSeq).groupMapReduce(_._1)(_._2)((r1, r2) => Seq(r1, r2).maxBy(_.level)(RightOrdering))
    val webhookRights = r1.webhooks.toSeq.concat(r2.webhooks.toSeq).groupMapReduce(_._1)(_._2)((r1, r2) => Seq(r1, r2).maxBy(_.level)(RightOrdering))

    TenantRight(
      level = level,
      projects = projectRights,
      keys = keyRights,
      webhooks = webhookRights,
      defaultProjectRight =  Ordering.Option(ProjectRightOrdering).max(r1.defaultProjectRight, r2.defaultProjectRight),
      defaultKeyRight = Ordering.Option(RightOrdering).max(r1.defaultKeyRight, r2.defaultKeyRight),
      defaultWebhookRight =  Ordering.Option(RightOrdering).max(r1.defaultWebhookRight, r2.defaultWebhookRight)
    )
  }
}

case class CompleteRights(rights: Rights, admin: Boolean) {
  def mergeWith(other: CompleteRights): CompleteRights = {
    val admin = this.admin || other.admin

    val tenantRights = this.rights.tenants.concat(other.rights.tenants).groupMapReduce(_._1)(_._2)((tr1, tr2) => keepHigher(tr1, tr2))

    CompleteRights(admin=admin, rights = Rights(tenantRights))

  }
}

case object CompleteRights {
  val EMPTY = CompleteRights(admin = false, rights = Rights.EMPTY)

  def writes: Writes[CompleteRights] = r => {
    val jsonRights = User.rightWrite.writes(r.rights)
    Json.obj("admin" -> r.admin, "rights" -> jsonRights)
  }

  def reads: Reads[CompleteRights] = json => {
    (for(
      admin <- (json \ "admin").asOpt[Boolean];
      rights <- (json \ "rights").asOpt[Rights](User.rightsReads)
    ) yield CompleteRights(admin = admin, rights = rights)
    ).map(r => JsSuccess(r)).getOrElse(JsError("Bad body format"))
  }
}