package fr.maif.izanami.web.formats

import fr.maif.izanami.models.features.EvaluationResultWrapper
import fr.maif.izanami.models.*
import fr.maif.izanami.v1.{OldFeature, OldGlobalScriptFeature}
import play.api.libs.json.{JsObject, Json}

object FeatureFormats {
  def writeFeatureForCheck(
      name: String,
      project: String,
      value: EvaluationResultWrapper
  ): JsObject = {
    Json.obj(
      "name" -> name,
      "active" -> Json.toJson(value)(EvaluationResultWrapper.writes),
      "project" -> project
    )
  }

  def writeFeatureInLegacyFormat(feature: AbstractFeature): JsObject = {
    feature match {
      case s: SingleConditionFeature =>
        Json
          .toJson(OldFeature.fromModernFeature(s))(OldFeature.oldFeatureWrites)
          .as[JsObject]
      // Transforming modern feature to script feature is a little hacky, however it's a format that legacy client
      // can understand, moreover due to the script nature of the feature, there won't be cache client side, which
      // is what we want since legacy client can't evaluate modern feeature locally
      case f: Feature =>
        Json
          .toJson(
            OldGlobalScriptFeature(
              id = f.id,
              name = f.name,
              enabled = f.enabled,
              description = Option(f.description),
              tags = f.tags,
              ref = "fake-script-feature"
            )
          )(OldFeature.oldGlobalScriptWrites)
          .as[JsObject]
      case w: LightWeightWasmFeature =>
        Json
          .toJson(OldFeature.fromScriptFeature(w))(OldFeature.oldFeatureWrites)
          .as[JsObject]
      case w: CompleteWasmFeature =>
        Json
          .toJson(OldFeature.fromScriptFeature(w))(OldFeature.oldFeatureWrites)
          .as[JsObject]
    }
  }
}
