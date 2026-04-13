package fr.maif.izanami.models

import fr.maif.izanami.web.ImportController.ImportConflictStrategy
import fr.maif.izanami.web.ImportController.FieldSpecificImportConflictStrategy

enum ConflictField:
  case FeatureName, FeatureDescription, FeatureId, FeatureEnabling,
    FeatureConditions, FeatureProject, FeatureTags

class ConflictStrategy(
    val defaultStrategy: ImportConflictStrategy,
    private val specificStrategies: Map[
      ConflictField,
      FieldSpecificImportConflictStrategy
    ]
) {
  def strategyFor(conflictField: ConflictField): ImportConflictStrategy =
    specificStrategies.getOrElse(conflictField, defaultStrategy)
}
