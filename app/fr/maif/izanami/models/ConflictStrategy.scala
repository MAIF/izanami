package fr.maif.izanami.models

import fr.maif.izanami.web.ImportController.FieldSpecificImportConflictStrategy
import fr.maif.izanami.web.ImportController.ImportConflictStrategy
import fr.maif.izanami.web.ImportController.MergeOverwrite

enum ConflictField:
  case FeatureName, FeatureDescription, FeatureEnabling,
    FeatureConditions, FeatureProject, FeatureTags

class ConflictStrategy(
    val defaultStrategy: ImportConflictStrategy,
    private val specificStrategies: Map[
      ConflictField,
      FieldSpecificImportConflictStrategy
    ]
) {

  def hasOverrideStrategy: Boolean =
    defaultStrategy == MergeOverwrite || specificStrategies.values.exists(s =>
      s == MergeOverwrite
    )
  def strategyFor(conflictField: ConflictField): ImportConflictStrategy =
    specificStrategies.getOrElse(conflictField, defaultStrategy)
}
