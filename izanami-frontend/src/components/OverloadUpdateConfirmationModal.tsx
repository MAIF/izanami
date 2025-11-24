import { useState } from "react";
import { TContext, TLightFeature } from "../utils/types";
import { ConditionDiff } from "./AuditLogs";
import { ImpactAnalysisResult } from "../utils/contextUtils";

export type OverloadUpdateConfirmationModalProps = {
  features: {
    [x: string]: Omit<ImpactAnalysisResult, "unprotectedUpdateAllowed"> & {
      newFeature: TLightFeature;
      oldFeature: TLightFeature;
      hasUserAdminRightOnFeature: boolean;
    };
  };
  onStrategyPreservationUpdate: (newValue: boolean) => any;
};

export function MultiFeatureOverloadUpdateConfirmationModal({
  features,
  onStrategyPreservationUpdate,
}: OverloadUpdateConfirmationModalProps) {
  const [strategyPreservation, setStrategyPreservation] = useState(false);
  const featureEntries = Object.values(features);
  const singleFeature = featureEntries.length === 1;
  const oldNewFeatures: [TLightFeature, TLightFeature][] = Object.values(
    features
  ).map(({ oldFeature, newFeature }) => {
    return [oldFeature, newFeature];
  });
  const { impactedContexts, rootImpactedContexts, forbiddenUpdateByProject } =
    featureEntries.reduce(
      (acc, impact) => {
        const featureName = impact.newFeature.name;
        const { impactedProtectedContexts, impactedRootProtectedContexts } =
          impact;

        impactedProtectedContexts.forEach((ctx) => {
          const name = ctx.parent.concat(ctx.name).join("/");
          if (!acc.impactedContexts[name]) {
            acc.impactedContexts[name] = [];
          }

          acc.impactedContexts[name].push(featureName);
        });

        impactedRootProtectedContexts.forEach((ctx) => {
          const name = ctx.parent.concat(ctx.name).join("/");
          if (!acc.rootImpactedContexts[name]) {
            acc.rootImpactedContexts[name] = [];
          }

          acc.rootImpactedContexts[name].push(featureName);
        });

        const project = impact.oldFeature.project;
        if (!impact.hasUserAdminRightOnFeature) {
          if (!acc.forbiddenUpdateByProject[project!]) {
            acc.forbiddenUpdateByProject[project!] = [];
          }

          acc.forbiddenUpdateByProject[project!].push(featureName);
        }
        return acc;
      },
      {
        impactedContexts: {},
        rootImpactedContexts: {},
        forbiddenUpdateByProject: {},
      } as {
        impactedContexts: { [x: string]: string[] };
        rootImpactedContexts: { [x: string]: string[] };
        forbiddenUpdateByProject: { [x: string]: string[] };
      }
    );

  return (
    <>
      <h3>Protected contexts impacts</h3>
      Updating {singleFeature ? "this feature" : "these features"} will impact
      below protected contexts, since neither them nor their parents define
      overload for {singleFeature ? "this feature" : "these features"}:
      <ul>
        {Object.entries(impactedContexts).map(([ctx, features]) => (
          <li key={ctx}>
            {ctx}
            {!singleFeature && `(${features.join(",")})`}
          </li>
        ))}
      </ul>
      {Object.keys(forbiddenUpdateByProject).length === 0 ? (
        <>
          These contexts strategies can be left unchanged by duplicating old
          strategy in below contexts:
          <ul>
            {Object.entries(rootImpactedContexts).map(([ctx, features]) => {
              return (
                <li key={ctx}>
                  {ctx}
                  {!singleFeature && `(${features.join(",")})`}
                </li>
              );
            })}
          </ul>
          <label
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <input
              style={{ marginTop: 0 }}
              type="checkbox"
              checked={strategyPreservation}
              className="izanami-checkbox"
              onChange={(e) => {
                setStrategyPreservation(e.target.checked);
                onStrategyPreservationUpdate(e.target.checked);
              }}
            ></input>
            &nbsp;
            <span style={{ fontSize: "16px" }}>
              Duplicate old strategy for these contexts
            </span>
          </label>
        </>
      ) : (
        <>
          You don't have enough rights to update these contexts, therefore{" "}
          <span className="fw-bold">
            old {singleFeature ? "strategy" : "strategies"} will be applied to
            below contexts
          </span>
          :
          <ul>
            {Object.entries(rootImpactedContexts).map(([ctx, features]) => {
              return (
                <li key={ctx}>
                  {ctx}
                  {!singleFeature && `(${features.join(",")})`}
                </li>
              );
            })}
          </ul>
        </>
      )}
      <h4>Changes</h4>
      <FeaturesDiffs oldNewfeatures={oldNewFeatures} />
    </>
  );
}

function FeaturesDiffs({
  oldNewfeatures,
}: {
  oldNewfeatures: [TLightFeature, TLightFeature][];
}) {
  return (
    <div className="accordion" id="diffAccordion">
      {oldNewfeatures.map(([oldFeature, newFeature]) => {
        const id = oldFeature.name.replaceAll(" ", "/");
        return (
          <div key={oldFeature.name} className="accordion-item">
            <h2 className="accordion-header">
              <button
                className="accordion-button collapsed"
                type="button"
                data-bs-toggle="collapse"
                data-bs-target={`#${id}`}
                aria-expanded="true"
                aria-controls={id}
              >
                {oldFeature.name}
              </button>
            </h2>
            <div
              id={id}
              className="accordion-collapse collapse"
              data-bs-parent="#diffAccordion"
            >
              <div className="accordion-body">
                <ConditionDiff
                  newConditions={newFeature}
                  oldConditions={oldFeature}
                />
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function OverloadUpdateConfirmationModal(props: {
  impactedProtectedContexts: (TContext & { parent: string[] })[];
  impactedRootProtectedContexts: (TContext & { parent: string[] })[];
  hasUserAdminRightOnFeature: boolean;
  onstrategyPreservationUpdate: (newValue: boolean) => any;
  oldFeature: TLightFeature;
  newFeature: TLightFeature;
}) {
  const features = {
    [props.oldFeature.id!]: {
      impactedProtectedContexts: props.impactedProtectedContexts,
      impactedRootProtectedContexts: props.impactedRootProtectedContexts,
      hasUserAdminRightOnFeature: props.hasUserAdminRightOnFeature,
      oldFeature: props.oldFeature,
      newFeature: props.newFeature,
    },
  };
  return (
    <MultiFeatureOverloadUpdateConfirmationModal
      features={features as any}
      onStrategyPreservationUpdate={props.onstrategyPreservationUpdate}
    />
  );
}
