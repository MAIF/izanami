import { useState } from "react";
import { TContext, TLightFeature } from "../utils/types";
import { ConditionDiff } from "./AuditLogs";
import { ImpactAnalysisResult } from "../utils/contextUtils";
import { Tooltip } from "./Tooltip";

export type OverloadUpdateConfirmationModalProps = {
  features: {
    [x: string]: Omit<ImpactAnalysisResult, "unprotectedUpdateAllowed"> & {
      newFeature?: TLightFeature;
      oldFeature?: TLightFeature;
      hasUserAdminRightOnFeature: boolean;
    };
  };
  onConfirm: (strategyPreservation: boolean) => Promise<any>;
  onCancel: () => any;
  context?: string;
};

export function MultiFeatureOverloadUpdateConfirmationModal({
  features,
  onConfirm,
  onCancel,
  context,
}: OverloadUpdateConfirmationModalProps) {
  const [confirmationText, setConfirmationText] = useState<string | undefined>(
    undefined
  );
  const [error, setError] = useState<undefined | string>(undefined);
  const featureEntries = Object.values(features);
  const singleFeature =
    featureEntries.length === 1
      ? featureEntries[0]?.oldFeature?.name ??
        featureEntries[0]?.newFeature?.name
      : undefined;
  const oldNewFeatures: [TLightFeature?, TLightFeature?][] = Object.values(
    features
  ).map(({ oldFeature, newFeature }) => {
    return [oldFeature, newFeature];
  });

  const isDelete = featureEntries.every((e) => !e.newFeature);
  const isCreate = featureEntries.every((e) => !e.oldFeature);
  const { impactedContexts, rootImpactedContexts, forbiddenUpdateByProject } =
    featureEntries.reduce(
      (acc, impact) => {
        const featureName =
          impact?.oldFeature?.name ?? impact?.newFeature?.name;
        const { impactedProtectedContexts, impactedRootProtectedContexts } =
          impact;

        impactedProtectedContexts.forEach((ctx) => {
          const name = ctx.parent.concat(ctx.name).join("/");
          if (!acc.impactedContexts[name]) {
            acc.impactedContexts[name] = [];
          }

          acc.impactedContexts[name].push(featureName!);
        });

        impactedRootProtectedContexts.forEach((ctx) => {
          const name = ctx.parent.concat(ctx.name).join("/");
          if (!acc.rootImpactedContexts[name]) {
            acc.rootImpactedContexts[name] = [];
          }

          acc.rootImpactedContexts[name].push(featureName!);
        });

        const project = impact?.oldFeature?.project;
        if (!impact.hasUserAdminRightOnFeature) {
          if (!acc.forbiddenUpdateByProject[project!]) {
            acc.forbiddenUpdateByProject[project!] = [];
          }

          acc.forbiddenUpdateByProject[project!].push(featureName!);
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

  const [strategyPreservation, setStrategyPreservation] = useState(
    Object.keys(forbiddenUpdateByProject).length > 0
  );

  return (
    <>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const names = featureEntries.map(
            ({ oldFeature, newFeature }) => oldFeature?.name ?? newFeature?.name
          );
          if (
            strategyPreservation ||
            names.some((name) => name === confirmationText)
          ) {
            onConfirm(strategyPreservation).then(() => onCancel());
          } else {
            setError("Name does not match");
          }
        }}
      >
        <div className="modal-header">
          <h3>Protected contexts impacts</h3>
        </div>
        <div className="modal-body">
          {isDelete ? "Deleting " : isCreate ? "Creating" : "Updating "}
          {singleFeature ? (
            <span className="fw-bold">{singleFeature}</span>
          ) : (
            "these features"
          )}{" "}
          {context ? (
            <>
              overload(s) for context <span className="fw-bold">{context}</span>{" "}
            </>
          ) : (
            ""
          )}
          will impact below protected contexts, since neither them nor their
          parents define overload for{" "}
          {singleFeature ? "this feature" : "these features"}:
          <ul>
            {Object.entries(impactedContexts).map(([ctx, features]) => (
              <li key={ctx}>
                {ctx}
                &nbsp;
                <i
                  className="fa-solid fa-lock fs-6 ml-5"
                  aria-label="protected"
                  aria-hidden
                ></i>
                {!singleFeature && (
                  <span className="fw-bold"> ({features.join(", ")})</span>
                )}
              </li>
            ))}
          </ul>
          {Object.keys(forbiddenUpdateByProject).length === 0 ? (
            <>
              These contexts strategies can be left unchanged by duplicating old
              strategy{" "}
              {Object.keys(impactedContexts).length !==
              Object.keys(rootImpactedContexts).length ? (
                <>
                  in below contexts:
                  <ul>
                    {Object.entries(rootImpactedContexts).map(
                      ([ctx, features]) => {
                        return (
                          <li key={ctx}>
                            {ctx}&nbsp;
                            <i
                              className="fa-solid fa-lock fs-6 ml-5"
                              aria-label="protected"
                              aria-hidden
                            ></i>
                            {!singleFeature && ` (${features.join(",")})`}
                          </li>
                        );
                      }
                    )}
                  </ul>
                </>
              ) : (
                "in them."
              )}
              <label
                style={{
                  marginTop: "1rem",
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
                    setError(undefined);
                    setStrategyPreservation(e.target.checked);
                  }}
                ></input>
                &nbsp;
                <span style={{ fontSize: "16px" }}>
                  Duplicate old strategy for these contexts
                  <Tooltip id="strategy-preservation-tooltip">
                    If you choose to duplicate old stragegy in impacted
                    contexts, Izanami will copy old
                    <br /> strategy of updated feature(s) in impacted contexts.
                    <br /> This will prevent "cascading update" and only{" "}
                    {context ? "current context" : "feature base strategy"} will
                    be impacted.
                  </Tooltip>
                </span>
              </label>
              {!strategyPreservation && (
                <div
                  style={{
                    marginTop: "1rem",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    flexDirection: "column",
                    fontSize: "16px",
                    fontWeight: "bold",
                  }}
                >
                  <label
                    style={{
                      fontSize: "16px",
                      maxWidth: "400px",
                    }}
                  >
                    <span style={{ alignSelf: "flex-start" }}>
                      Type{" "}
                      {singleFeature
                        ? "feature name"
                        : "one of the feature names"}{" "}
                      below to confirm.
                    </span>
                    <input
                      className="form-control"
                      type="text"
                      onChange={(e) => {
                        setError(undefined);
                        setConfirmationText(e?.target?.value);
                      }}
                    />
                    {error && <div className="error-message">{error}</div>}
                  </label>
                </div>
              )}
            </>
          ) : (
            <>
              You don't have enough rights to update these contexts, therefore{" "}
              <span className="fw-bold">
                old {singleFeature ? "strategy" : "strategies"} will be applied
                to below contexts
              </span>
              :
              <ul>
                {Object.entries(rootImpactedContexts).map(([ctx, features]) => {
                  return (
                    <li key={ctx}>
                      {ctx}&nbsp;
                      <i
                        className="fa-solid fa-lock fs-6 ml-5"
                        aria-label="protected"
                        aria-hidden
                      ></i>
                      {!singleFeature && ` (${features.join(",")})`}
                    </li>
                  );
                })}
              </ul>
            </>
          )}
          {!isDelete && !isCreate && (
            <>
              {" "}
              <h4>Changes</h4>
              <FeaturesDiffs oldNewfeatures={oldNewFeatures as any} />
            </>
          )}
        </div>
        <div className="modal-footer">
          <button
            type="button"
            className="btn btn-danger-light"
            data-bs-dismiss="modal"
            onClick={() => onCancel()}
          >
            Close
          </button>

          <button
            type="submit"
            className="btn btn-primary"
            aria-label="Confirm"
          >
            Confirm
          </button>
        </div>
      </form>
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
  oldFeature?: TLightFeature;
  newFeature?: TLightFeature;
  onConfirm: (strategyPreservation: boolean) => Promise<any>;
  onCancel: () => any;
  context?: string;
}) {
  const id = props?.oldFeature?.id ?? props?.newFeature?.id;
  const features = {
    [id!]: {
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
      onConfirm={props?.onConfirm}
      onCancel={props?.onCancel}
      context={props.context}
    />
  );
}
