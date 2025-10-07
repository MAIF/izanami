package fr.maif.izanami.requests

import fr.maif.izanami.events.EventAuthentication
import fr.maif.izanami.models.{CompleteContextualStrategy, UserWithCompleteRightForOneTenant}
import fr.maif.izanami.web.{FeatureContextPath, UserInformation}

case class OverloadUpsertRequest(tenant: String,
                                 project: String,
                                 parents: FeatureContextPath,
                                 name: String,
                                 preserveProtectedContexts: Boolean,
                                 strategy: CompleteContextualStrategy,
                                 user: UserWithCompleteRightForOneTenant,
                                 authentication: EventAuthentication
                                )
