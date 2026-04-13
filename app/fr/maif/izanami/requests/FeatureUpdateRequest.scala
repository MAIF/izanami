package fr.maif.izanami.requests

import fr.maif.izanami.events.EventAuthentication
import fr.maif.izanami.models.CompleteContextualStrategy
import fr.maif.izanami.models.CompleteFeature
import fr.maif.izanami.models.UserWithCompleteRightForOneTenant
import fr.maif.izanami.web.FeatureContextPath
import fr.maif.izanami.web.StandardUserInformation
import fr.maif.izanami.web.UserInformation

sealed trait FeatureUpdateRequest {
  def tenant: String
  def project: String
  def user: UserWithCompleteRightForOneTenant
  def authentication: EventAuthentication
  def preserveProtectedContexts: Boolean
  def tags: Set[String]
  def strategy: CompleteContextualStrategy
  def featureName: String
  def userInformation: UserInformation = StandardUserInformation(
    username = user.username,
    authentication = authentication
  )
  def maybeContext: Option[FeatureContextPath]
}

case class BaseFeatureUpdateRequest(
    tenant: String,
    user: UserWithCompleteRightForOneTenant,
    authentication: EventAuthentication,
    preserveProtectedContexts: Boolean,
    feature: CompleteFeature,
    id: String
) extends FeatureUpdateRequest {
  def project: String = feature.project
  def tags: Set[String] = feature.tags
  def strategy: CompleteContextualStrategy =
    feature.toCompleteContextualStrategy
  def featureName: String = feature.name

  override def maybeContext: Option[FeatureContextPath] = None
}

case class OverloadFeatureUpdateRequest(
    tenant: String,
    project: String,
    user: UserWithCompleteRightForOneTenant,
    authentication: EventAuthentication,
    preserveProtectedContexts: Boolean,
    strategy: CompleteContextualStrategy,
    context: FeatureContextPath,
    featureName: String
) extends FeatureUpdateRequest {
  def tags = Set()
  override def maybeContext: Option[FeatureContextPath] = Some(context)
}
