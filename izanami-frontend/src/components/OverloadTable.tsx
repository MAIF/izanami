import { NavLink, useParams } from "react-router-dom";
import {
  TContext,
  TContextOverload,
  TProjectLevel,
  TUser,
} from "../utils/types";
import { ColumnDef, Row } from "@tanstack/react-table";
import { useMutation } from "@tanstack/react-query";
import { useContext, useState } from "react";
import { Tooltip as LocalToolTip } from "./Tooltip";
import {
  deleteFeatureActivationForContext,
  updateFeatureActivationForContext,
} from "../utils/queries";
import {
  hasRightForProject,
  hasRightForTenant,
  IzanamiContext,
} from "../securityContext";
import {
  analyzeUpdateImpact,
  findContextForPath,
  findImpactedProtectedContexts,
} from "../utils/contextUtils";
import { GlobalContextIcon } from "../utils/icons";
import { ResultTypeIcon } from "./ResultTypeIcon";
import { OverloadUpdateConfirmationModal } from "./OverloadUpdateConfirmationModal";
import { TextualFeatureDetails } from "./FeatureDetails";
import { OverloadCreationForm } from "./OverloadCreationForm";
import { ExistingFeatureTestForm } from "./ExistingFeatureTestForm";
import { GenericTable } from "./GenericTable";

type OverloadFields = "name" | "enabled" | "details" | "path" | "linkedPath";
type OverloadActionNames = "delete" | "edit" | "test";

export function OverloadTable(props: {
  project?: string;
  overloads: TContextOverload[];
  fields: OverloadFields[];
  actions: (t: TContextOverload) => OverloadActionNames[];
  refresh: () => any;
  contexts: TContext[];
}) {
  const { tenant } = useParams();
  const { fields, overloads, actions, refresh, project, contexts } = props;
  const columns: ColumnDef<TContextOverload>[] = [];
  const updateStrategyMutation = useMutation({
    mutationFn: (
      data: TContextOverload & {
        feature: string;
        project: string;
        strategyPreservation: boolean;
      }
    ) => {
      return updateFeatureActivationForContext(
        tenant!,
        data.project,
        data.path,
        data.feature,
        data.enabled,
        data.resultType,
        data.strategyPreservation,
        "conditions" in data ? data.conditions : undefined,
        "wasmConfig" in data ? data.wasmConfig : undefined,
        "value" in data ? data.value : undefined
      );
    },

    onSuccess: () => {
      refresh();
    },
  });

  const deleteStrategyMutation = useMutation({
    mutationFn: (data: {
      feature: string;
      path: string;
      project: string;
      strategyPreservation: boolean;
    }) =>
      deleteFeatureActivationForContext(
        tenant!,
        data.project, // TODO this should not be necessary
        data.path,
        data.feature,
        data.strategyPreservation
      ),

    onSuccess: () => {
      refresh();
    },
  });

  const { askInputConfirmation, user, displayModal, askConfirmation } =
    useContext(IzanamiContext);
  const contextByPath = new Map(
    overloads
      .map((o) => o.path)
      .map((p) => [p, findContextForPath(p, contexts)])
  );

  const hasPath = fields.includes("path");
  const hasLinkedPath = fields.includes("linkedPath");
  if (hasPath || hasLinkedPath) {
    columns.push({
      accessorKey: "path",
      header: () => "Overload path",
      cell: (info: any) => {
        const context = contextByPath.get(info.getValue());
        return hasLinkedPath ? (
          <NavLink
            to={`/tenants/${tenant}/projects/${project}/contexts?open=["${info.getValue()}"]`}
          >
            {info.getValue()}
            {context?.protected && (
              <>
                &nbsp;
                <i className="fa-solid fa-lock fs-6" aria-label="protected"></i>
              </>
            )}
            {context?.global && (
              <>
                &nbsp;
                <GlobalContextIcon />
              </>
            )}
          </NavLink>
        ) : (
          info.getValue()
        );
      },
      minSize: 350,
      size: 15,
    });
  }
  if (fields.includes("name")) {
    columns.push({
      accessorKey: "name",
      header: () => "Feature name",
      cell: (props: { row: Row<any> }) => {
        const feature = props.row.original;
        return (
          <div className="d-flex justify-start align-items-center">
            <ResultTypeIcon resultType={feature.resultType} />
            <span className="px-1">{feature.name}</span>
          </div>
        );
      },
      minSize: 150,
      size: 15,
    });
  }
  if (fields.includes("enabled")) {
    columns.push({
      accessorKey: "enabled",
      header: () => "Enabled",
      cell: (info: any) => {
        const feature = info.row.original as TContextOverload;
        const isEnabled = feature.enabled;

        const hasUpdateRight = hasRightForProject(
          user!,
          TProjectLevel.Update,
          feature.project!,
          tenant!
        );

        return (
          <label
            style={{
              cursor: "pointer",
              display: "flex",
              flexDirection: "row",
              alignItems: "center",
            }}
          >
            {hasUpdateRight && (
              <>
                <input
                  checked={isEnabled ? true : false}
                  disabled={!hasUpdateRight}
                  type="checkbox"
                  className="izanami-checkbox"
                  style={{ marginTop: 0 }}
                  aria-label={`${isEnabled ? "Disable" : "Enable"} ${
                    feature.name
                  }`}
                  onChange={() => {
                    const context = contextByPath.get(feature.path);
                    const {
                      impactedProtectedContexts,
                      impactedRootProtectedContexts,
                      unprotectedUpdateAllowed,
                    } = analyzeUpdateImpact({
                      contexts: contexts,
                      project: feature.project!,
                      tenant: tenant!,
                      user: user!,
                      updatedFeatureId: feature.id,
                      updatedContext: feature.path ?? "" + "/" + context?.name,
                    });

                    if (
                      context?.protected &&
                      impactedProtectedContexts.length === 0
                    ) {
                      askInputConfirmation(
                        <>
                          Updating this overload will impact protected context{" "}
                          {context.name}.<br />
                          Please type feature name below to confirm.
                          <LocalToolTip id="overload-update-confirmation">
                            Typing feature name is required since this overload
                            is for a protected context.
                          </LocalToolTip>
                        </>,
                        () => {
                          return updateStrategyMutation.mutateAsync({
                            feature: feature.name,
                            project: feature.project!,
                            ...feature,
                            enabled: !isEnabled,
                            strategyPreservation: false,
                          });
                        },
                        feature.name,
                        `Updating feature ${feature.name}`
                      );
                    } else if (impactedProtectedContexts.length > 0) {
                      return displayModal(({ close }) => (
                        <OverloadUpdateConfirmationModal
                          impactedProtectedContexts={impactedProtectedContexts}
                          impactedRootProtectedContexts={
                            impactedRootProtectedContexts
                          }
                          hasUserAdminRightOnFeature={unprotectedUpdateAllowed}
                          oldFeature={feature as any}
                          newFeature={
                            { ...feature, enabled: !isEnabled } as any
                          }
                          onCancel={() => close()}
                          onConfirm={(strategyPreservation) =>
                            updateStrategyMutation.mutateAsync({
                              feature: feature.name,
                              project: feature.project!,
                              ...feature,
                              enabled: !isEnabled,
                              strategyPreservation: strategyPreservation,
                            })
                          }
                          context={feature.path}
                        />
                      ));
                    } else {
                      updateStrategyMutation.mutateAsync({
                        feature: feature.name,
                        project: feature.project!,
                        ...feature,
                        enabled: !isEnabled,
                        strategyPreservation: false,
                      });
                    }
                  }}
                />
                &nbsp;
              </>
            )}
            <span
              className={`activation-status ${
                isEnabled ? "enabled" : "disabled"
              }-status`}
            >
              {isEnabled ? "Enabled" : "Disabled"}
            </span>
          </label>
        );
      },
      minSize: 150,
      size: 5,
      meta: {
        valueType: "status",
      },
    });
  }
  if (fields.includes("details")) {
    columns.push({
      id: "details",
      header: () => "Details",
      minSize: 250,
      cell: (props: any) => (
        <div className="d-flex">
          <div>
            <TextualFeatureDetails feature={props?.row?.original} />
          </div>
        </div>
      ),
    });
  }

  const customActions = {
    edit: {
      icon: (
        <>
          <i className="bi bi-pencil-square" aria-hidden></i> Edit
        </>
      ),
      hasRight: (user: TUser, overload: TContextOverload) => {
        const canEdit = actions(overload).includes("edit");
        const context = contextByPath.get(overload.path);
        if (context?.protected) {
          if (context.global && hasRightForTenant(user, tenant!, "Admin")) {
            return canEdit;
          } else if (
            hasRightForProject(user, "Admin", overload.project!, tenant!)
          ) {
            return canEdit;
          } else {
            return false;
          }
        } else {
          return canEdit;
        }
      },
      customForm: (datum: TContextOverload, cancel: () => void) => {
        const context = contextByPath.get(datum.path);
        return (
          <>
            <h4>Edit overload</h4>
            <OverloadCreationForm
              resultType={datum.resultType}
              project={datum.project!}
              defaultValue={datum}
              submit={(overload) => {
                const {
                  impactedProtectedContexts,
                  impactedRootProtectedContexts,
                  unprotectedUpdateAllowed,
                } = analyzeUpdateImpact({
                  contexts: contexts,
                  project: datum.project!,
                  tenant: tenant!,
                  user: user!,
                  updatedFeatureId: datum.id,
                  updatedContext: datum.path ?? "" + "/" + context?.name,
                });

                if (
                  context?.protected &&
                  impactedProtectedContexts.length === 0
                ) {
                  askInputConfirmation(
                    <>
                      Updating this overload will impact protected context{" "}
                      {context.name}.<br />
                      Please type feature name below to confirm.
                      <LocalToolTip id="overload-update-confirmation">
                        Typing feature name is required since this overload is
                        for a protected context.
                      </LocalToolTip>
                    </>,
                    () => {
                      return updateStrategyMutation.mutateAsync(
                        {
                          feature: overload.name,
                          strategyPreservation: false,
                          ...overload,
                        } as any,
                        {
                          onSuccess: () => cancel(),
                        }
                      );
                    },
                    datum.name,
                    `Updating feature ${datum.name}`
                  );
                } else if (impactedProtectedContexts.length > 0) {
                  return displayModal(({ close }) => (
                    <OverloadUpdateConfirmationModal
                      impactedProtectedContexts={impactedProtectedContexts}
                      impactedRootProtectedContexts={
                        impactedRootProtectedContexts
                      }
                      hasUserAdminRightOnFeature={unprotectedUpdateAllowed}
                      oldFeature={datum as any}
                      newFeature={overload as any}
                      onCancel={() => close()}
                      onConfirm={(strategyPreservation) => {
                        return updateStrategyMutation.mutateAsync(
                          {
                            feature: overload.name,
                            ...overload,
                            strategyPreservation: strategyPreservation,
                          } as any,
                          {
                            onSuccess: () => cancel(),
                          }
                        );
                      }}
                      context={datum.path}
                    />
                  ));
                } else {
                  updateStrategyMutation.mutateAsync(
                    {
                      feature: overload.name,
                      ...overload,
                      strategyPreservation: false,
                    } as any,
                    {
                      onSuccess: () => cancel(),
                    }
                  );
                }
              }}
              cancel={cancel}
              noName
            />
          </>
        );
      },
    },
    test: {
      icon: (
        <>
          <i className="bi bi-wrench" aria-hidden></i> Test overload
        </>
      ),
      hasRight: (user: TUser, overload: TContextOverload) => {
        return actions(overload).includes("test");
      },
      customForm: (datum: TContextOverload, cancel: () => void) => {
        return (
          <ExistingFeatureTestForm
            context={datum.path}
            feature={datum}
            cancel={cancel}
          />
        );
      },
    },
    delete: {
      icon: (
        <>
          <i className="bi bi-trash" aria-hidden></i> Delete
        </>
      ),
      hasRight: (user: TUser, overload: TContextOverload) => {
        const context = contextByPath.get(overload.path);
        const canDelete = actions(overload).includes("delete");
        if (context?.protected) {
          if (context.global && hasRightForTenant(user, tenant!, "Admin")) {
            return canDelete;
          } else if (
            hasRightForProject(user, "Admin", overload.project!, tenant!)
          ) {
            return canDelete;
          } else {
            return false;
          }
        } else {
          return canDelete;
        }
      },
      action: (overload: TContextOverload) => {
        const context = contextByPath.get(overload.path);

        const {
          impactedProtectedContexts,
          impactedRootProtectedContexts,
          unprotectedUpdateAllowed,
        } = analyzeUpdateImpact({
          contexts: contexts,
          project: overload.project!,
          tenant: tenant!,
          user: user!,
          updatedFeatureId: overload.id,
          updatedContext: overload.path ?? "" + "/" + context?.name,
        });

        if (context?.protected && impactedProtectedContexts.length === 0) {
          askInputConfirmation(
            <>
              Deleting this overload will impact protected context{" "}
              {context.name}.<br />
              Please type feature name below to confirm.
              <LocalToolTip id="overload-update-confirmation">
                Typing feature name is required since this overload is for a
                protected context.
              </LocalToolTip>
            </>,
            () =>
              deleteStrategyMutation.mutateAsync({
                feature: overload.name,
                path: overload.path!,
                project: overload.project as any,
                strategyPreservation: false,
              }),
            overload.name,
            `Deleting ${overload.path} overload for feature ${overload.name}`
          );
        } else if (impactedProtectedContexts.length > 0) {
          return displayModal(({ close }) => (
            <OverloadUpdateConfirmationModal
              impactedProtectedContexts={impactedProtectedContexts}
              impactedRootProtectedContexts={impactedRootProtectedContexts}
              hasUserAdminRightOnFeature={unprotectedUpdateAllowed}
              oldFeature={overload as any}
              onCancel={() => close()}
              onConfirm={(strategyPreservation) =>
                deleteStrategyMutation.mutateAsync({
                  feature: overload.name,
                  path: overload.path!,
                  project: overload.project as any,
                  strategyPreservation,
                })
              }
              context={overload.path}
            />
          ));
        } else {
          return askConfirmation(
            <>
              Are you sure you want to delete feature{" "}
              <span className="fw-bold">{overload.name}</span> overload for
              context <span className="fw-bold">{overload.path}</span> ?
            </>,
            () => {
              return deleteStrategyMutation.mutateAsync({
                feature: overload.name,
                path: overload.path!,
                project: overload.project as any,
                strategyPreservation: false,
              });
            }
          );
        }
      },
    },
  };

  return (
    <GenericTable
      idAccessor={(datum: TContextOverload) =>
        `${datum.path ?? ""} ${datum.name}`
      }
      columns={columns}
      data={overloads}
      customRowActions={customActions}
      defaultSort="name"
    />
  );
}
