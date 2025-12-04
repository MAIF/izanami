import * as React from "react";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  TContext,
  TContextOverload,
  TLevel,
  TLightFeature,
  TUser,
  TCompleteFeature,
  StaleStatus,
  TProjectLevel,
} from "../utils/types";
import { format } from "date-fns";
import { useMutation, useQueries, useQuery } from "@tanstack/react-query";
import {
  createFeature,
  deleteFeature,
  patchFeatures,
  projectContextKey,
  projectQueryKey,
  queryContextsForProject,
  queryTenant,
  tagsQueryKey,
  tenantQueryKey,
  updateFeature,
  queryTags,
  updateFeatureActivationForContext,
} from "../utils/queries";
import {
  IzanamiContext,
  hasRightForProject,
  hasRightForTenant,
  useProjectRight,
} from "../securityContext";
import { GenericTable, TCustomAction } from "./GenericTable";
import { ColumnDef, Row } from "@tanstack/react-table";
import queryClient from "../queryClient";
import { FeatureForm } from "./FeatureForm";
import { OverloadCreationForm } from "./OverloadCreationForm";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { Tooltip } from "react-tooltip";

import { Form } from "./Form";
import { Loader } from "./Loader";
import MultiSelect, { Option } from "./MultiSelect";
import { constraints } from "@maif/react-forms";
import { GlobalContextIcon } from "../utils/icons";
import {
  analyzeUpdateImpact,
  extractContextsMatching,
  findContextWithOverloadsForFeature,
  findOverloadsForFeature,
  ImpactAnalysisResult,
  possiblePaths,
} from "../utils/contextUtils";
import { TextualFeatureDetails } from "./FeatureDetails";
import {
  MultiFeatureOverloadUpdateConfirmationModal,
  OverloadUpdateConfirmationModal,
} from "./OverloadUpdateConfirmationModal";
import { OverloadTable } from "./OverloadTable";
import { ExistingFeatureTestForm } from "./ExistingFeatureTestForm";
import { isEqual } from "lodash";

type FeatureFields =
  | "id"
  | "name"
  | "tags"
  | "enabled"
  | "details"
  | "project"
  | "overloadCount";

type FeatureActionNames =
  | "delete"
  | "edit"
  | "test"
  | "overloads"
  | "duplicate"
  | "transfer"
  | "url";

export const Strategy = {
  all: { id: "All", label: "All" },
  percentage: { id: "Percentage", label: "Percentage" },
  userList: { id: "UserList", label: "User list" },
} as const;

export const FeatureType = {
  script: { value: "Script", label: "Scripted feature" },
  classical: { value: "Classical", label: "Classical feature" },
} as const;

const BULK_OPERATIONS = [
  "Enable",
  "Disable",
  "Delete",
  "Transfer",
  "Apply Tags",
] as const;

const UPDATE_BULK_OPERATIONS = ["Enable", "Disable", "Apply Tags"] as const;

function OperationButton(props: {
  tenant: string;
  bulkOperation: string;
  selectedRows: TLightFeature[];
  cancel: () => void;
  refresh: () => any;
  validationCallback: (
    action: string,
    features: TLightFeature[]
  ) => Promise<any>;
}) {
  const {
    tenant,
    bulkOperation,
    selectedRows,
    cancel,
    refresh,
    validationCallback,
  } = props;
  const hasSelectedRows = selectedRows.length > 0;
  const { askConfirmation } = React.useContext(IzanamiContext);
  return (
    <>
      <button
        className="ms-2 btn btn-primary"
        type="button"
        disabled={!hasSelectedRows || !bulkOperation}
        onClick={() => {
          validationCallback(bulkOperation, selectedRows).then(() => {
            switch (bulkOperation) {
              case "Delete":
                askConfirmation(
                  `Are you sure you want to delete ${
                    selectedRows.length
                  } feature${selectedRows.length > 1 ? "s" : ""} ?`,
                  () =>
                    patchFeatures(
                      tenant!,
                      selectedRows.map((f) => ({
                        op: "remove",
                        path: `/${f.id}`,
                      }))
                    ).then(() => refresh())
                );
                break;
              case "Enable":
                patchFeatures(
                  tenant!,
                  selectedRows
                    .filter((f) => !f.enabled)
                    .map((f) => ({
                      op: "replace",
                      path: `/${f.id}/enabled`,
                      value: true,
                    }))
                )
                  .then(() => refresh())
                  .then(() => {
                    cancel();
                  });
                break;
              case "Disable":
                patchFeatures(
                  tenant!,
                  selectedRows
                    .filter((f) => f.enabled)
                    .map((f) => ({
                      op: "replace",
                      path: `/${f.id}/enabled`,
                      value: false,
                    }))
                )
                  .then(() => refresh())
                  .then(() => cancel());
                break;
            }
          });
        }}
      >
        {bulkOperation} {selectedRows.length} feature
        {selectedRows.length > 1 ? "s" : ""}
      </button>
    </>
  );
}

function OperationTransferForm(props: {
  tenant: string;
  selectedRows: TLightFeature[];
  cancel: () => void;
  refresh: () => any;
  validationCallback: (
    action: string,
    features: TLightFeature[]
  ) => Promise<any>;
}) {
  const { tenant, selectedRows, cancel, refresh, validationCallback } = props;
  const selectedRowProjects = selectedRows.map((f) => f.project);
  const selectedRowProject = selectedRowProjects.filter(
    (q, idx) => selectedRowProjects.indexOf(q) === idx
  );

  const projectQuery = useQuery({
    queryKey: [tenantQueryKey(tenant)],

    queryFn: () => queryTenant(tenant),
  });
  const { askConfirmation } = React.useContext(IzanamiContext);

  if (projectQuery.isLoading) {
    return <Loader message="Loading projects..." />;
  } else if (projectQuery.error) {
    return <div className="error">Failed to load projects</div>;
  } else {
    return (
      <Form
        className={"d-flex align-items-center"}
        schema={{
          project: {
            className: "form-margin",
            label: () => "",
            type: "string",
            format: "select",
            props: { styles: customStyles, "aria-label": "Target project" },
            placeholder: "Select target project...",
            options: projectQuery.data?.projects
              ?.filter(({ name }) => selectedRowProject.indexOf(name) === -1)
              ?.map(({ name }) => ({
                label: name,
                value: name,
              })),
            constraints: [constraints.required("Target project is required")],
          },
        }}
        onSubmit={(data: { project: string }) => {
          return validationCallback("Transfer", selectedRows).then(() =>
            askConfirmation(
              `Transferring ${selectedRows.length} feature${
                selectedRows.length > 1 ? "s" : ""
              }  will delete existing local overloads (if any), are you sure ?`,
              () =>
                patchFeatures(
                  tenant!,
                  selectedRows.map((f) => ({
                    op: "replace",
                    path: `/${f.id}/project`,
                    value: data.project,
                  }))
                )
                  .then(refresh)
                  .then(cancel)
            )
          );
        }}
        footer={({ valid }: { valid: () => void }) => {
          return (
            <div className="d-flex justify-content-end">
              <button
                type="button"
                className="btn btn-danger-light m-2"
                onClick={cancel}
              >
                Cancel
              </button>
              <button className="btn btn-primary m-2" onClick={valid}>
                Transfer {selectedRows.length} feature
                {selectedRows.length > 1 ? "s" : ""}
              </button>
            </div>
          );
        }}
      />
    );
  }
}

function OperationTagForm(props: {
  tenant: string;
  selectedRows: TLightFeature[];
  cancel: () => void;
  refresh: () => any;
  validationCallback: (
    action: string,
    features: TLightFeature[]
  ) => Promise<any>;
}) {
  const { tenant, selectedRows, cancel, refresh, validationCallback } = props;
  const tagsQuery = useQuery({
    queryKey: [tagsQueryKey(tenant)],
    queryFn: () => queryTags(tenant),
  });
  const selectedRowTags = [...new Set(selectedRows.flatMap((f) => f.tags))];
  const { askConfirmation } = React.useContext(IzanamiContext);
  const [values, setSelectedValues] = React.useState<Option[] | null>();

  const onSelected = (selectedOptions: Option[]) => {
    setSelectedValues(selectedOptions);
  };
  const OnSubmit = (selectedRows: TLightFeature[], values: Option[]) => {
    validationCallback("Apply Tags", selectedRows).then(() =>
      askConfirmation(
        `Are you sure to apply ${values.length} tag${
          selectedRows.length > 1 ? "s" : ""
        } on ${selectedRows.length} feature${
          selectedRows.length > 1 ? "s" : ""
        }?`,
        () =>
          patchFeatures(
            tenant!,
            selectedRows.map((f) => ({
              op: "replace",
              path: `/${f.id}/tags`,
              value: [...new Set(values.map((value) => value.value))],
            }))
          )
            .then(refresh)
            .then(cancel)
      )
    );
  };
  if (tagsQuery.isLoading) {
    return <Loader message="Loading tags..." />;
  } else if (tagsQuery.error) {
    return <div className="error">Failed to load tags</div>;
  } else {
    const selectedTags = selectedRows.flatMap((row) => row.tags);
    const tagCounts = new Map();
    selectedTags.forEach((tag) => {
      tagCounts.set(tag, (tagCounts.get(tag) || 0) + 1);
    });
    const nonRepeatingTags =
      selectedRows.length > 1
        ? [...tagCounts.entries()]
            .filter(([, count]) => count < selectedRows.length)
            .map(([tag]) => tag)
        : [];
    const dataTags = (tagsQuery.data ?? []).map(({ name }) => ({
      label: name,
      value: name,
      checked: selectedRowTags.includes(name),
      indeterminate: nonRepeatingTags.includes(name),
    }));

    return (
      <>
        <MultiSelect
          options={dataTags}
          value={values!}
          defaultValue={dataTags.filter((f) => f.checked)}
          onSelected={onSelected}
          labelBy={"Select tags..."}
        />
        <button
          className="btn btn-primary m-2"
          onClick={() =>
            OnSubmit(
              selectedRows,
              values ? values : dataTags.filter((f) => f.checked)
            )
          }
        >
          Update {selectedRows.length} feature
          {selectedRows.length > 1 ? "s" : ""}
        </button>
      </>
    );
  }
}

function TransferForm(props: {
  tenant: string;
  project: string;
  feature: TLightFeature;
  cancel: () => void;
  validationCallback: (features: TLightFeature[]) => Promise<any>;
}) {
  const { project, tenant, feature, cancel, validationCallback } = props;
  const projectQuery = useQuery({
    queryKey: [tenantQueryKey(tenant)],
    queryFn: () => queryTenant(tenant),
  });

  const featureUpdateMutation = useMutation({
    mutationFn: (data: { project: string }) =>
      patchFeatures(tenant, [
        {
          op: "replace",
          path: `/${feature.id}/project`,
          value: data.project,
        },
      ]).then(() => {
        queryClient.invalidateQueries({
          queryKey: [projectQueryKey(tenant, project)],
        });
      }),
  });

  const { askConfirmation } = React.useContext(IzanamiContext);

  if (projectQuery.isLoading) {
    return <Loader message="Loading projects..." />;
  } else if (projectQuery.error) {
    return <div className="error">Failed to load projects</div>;
  } else {
    return (
      <Form
        schema={{
          project: {
            required: true,
            label: "Target project",
            type: "string",
            format: "select",
            props: {
              "aria-label": "projects",
              styles: customStyles,
              menuPlacement: "top",
            },
            options: projectQuery.data?.projects
              ?.filter(({ name }) => name !== project)
              ?.map(({ name }) => ({
                label: name,
                value: name,
              })),
          },
        }}
        onSubmit={(data) => {
          return validationCallback([feature]).then(() =>
            askConfirmation(
              "Transferring this feature will delete existing local overloads (if any), are you sure ?",
              () => featureUpdateMutation.mutateAsync(data as any)
            )
          );
        }}
        onClose={() => cancel()}
        submitText="Transfer feature"
      />
    );
  }
}

function OperationForm(props: {
  tenant: string;
  bulkOperation: string;
  selectedRows: TLightFeature[];
  cancel: () => void;
  refresh: () => any;
  validationCallback: (
    action: string,
    features: TLightFeature[]
  ) => Promise<any>;
}) {
  const {
    tenant,
    bulkOperation,
    selectedRows,
    cancel,
    refresh,
    validationCallback,
  } = props;
  switch (bulkOperation) {
    case "Transfer":
      return (
        <OperationTransferForm
          tenant={tenant!}
          selectedRows={selectedRows}
          cancel={cancel}
          refresh={refresh}
          validationCallback={validationCallback}
        />
      );
    case "Apply Tags":
      return (
        <OperationTagForm
          tenant={tenant!}
          selectedRows={selectedRows}
          cancel={cancel}
          refresh={refresh}
          validationCallback={validationCallback}
        />
      );
    default:
      return (
        <OperationButton
          tenant={tenant!}
          bulkOperation={bulkOperation}
          selectedRows={selectedRows}
          cancel={cancel}
          refresh={refresh}
          validationCallback={validationCallback}
        />
      );
  }
}

export function CopyButton(props: { value: any; title?: any }) {
  const [validCheckMark, setValidCheckmark] = React.useState<boolean>(false);
  const [idCheckMark, setIdCheckmark] = React.useState<number>();
  const timeRef = React.useRef<NodeJS.Timeout | undefined>(undefined);
  const { value, title } = props;

  return (
    <button
      className="btn btn-secondary btn-sm"
      onClick={() => {
        navigator.clipboard.writeText(value);
        if (timeRef.current) {
          clearTimeout(timeRef.current);
        }
        setValidCheckmark(true);
        setIdCheckmark(value);
        timeRef.current = setTimeout(() => {
          setValidCheckmark(false);
        }, 3000);
      }}
    >
      {validCheckMark && idCheckMark === value ? (
        <>
          <>
            <i
              aria-label="copy feature id"
              className={`${
                title ? "bi bi-clipboard me-2" : "bi bi-clipboard"
              }`}
              data-tooltip-id="copy_id"
              data-tooltip-content="Copied to clipboard !"
              data-tooltip-place="top"
            ></i>
            {title ? title : ""}
            <Tooltip id="copy_id" isOpen={validCheckMark} />
          </>
        </>
      ) : (
        <>
          <i
            className={`${title ? "bi bi-clipboard me-2" : "bi bi-clipboard"}`}
            aria-label="copy feature id"
          ></i>
          {title ? title : ""}
        </>
      )}
    </button>
  );
}

function FeatureUrl(props: {
  context?: string;
  tenant: string;
  feature: TLightFeature;
}) {
  const { project } = props.feature;
  const { tenant, context, feature } = props;
  const contextQuery = useQuery({
    queryKey: [projectContextKey(tenant!, project!)],

    queryFn: () => queryContextsForProject(tenant!, project!),
  });
  const [selectedContext, setSelectedContext] = useState(context);

  const { expositionUrl } = React.useContext(IzanamiContext);

  const url = `${
    expositionUrl ? expositionUrl : "<BASE-IZANAMI-URL>"
  }/api/v2/features/${feature.id}${
    selectedContext ? "?context=" + encodeURIComponent(selectedContext) : ""
  }`;

  if (contextQuery.error) {
    return <div>Failed to fetch contexts</div>;
  } else if (contextQuery.data) {
    const allContexts = possiblePaths(contextQuery.data).map(
      ({ path }) => path
    );

    return (
      <>
        <div>
          <h5>Client link for {feature.name}</h5>
          <span>
            To craft more complete client queries, use{" "}
            <a href={`/tenants/${tenant}/query-builder`}>the query builder</a>.
          </span>
          <label style={{ width: "100%" }}>
            Context (optional)
            <Select
              defaultValue={
                context ? { label: context, value: context } : undefined
              }
              onChange={(e) => {
                setSelectedContext(e?.value);
              }}
              styles={customStyles}
              options={allContexts
                .map((c) => c.substring(1))
                .sort()
                .map((c) => ({ value: c, label: c }))}
              isClearable
            />
          </label>
        </div>
        <div className="mt-3">
          <label htmlFor="url">
            Feature <b>{feature.name}</b> url{" "}
            {`${selectedContext ? "for context " + selectedContext + " " : ""}`}
            is
          </label>
        </div>
        <div className="input-group mb-3">
          <input
            name="url"
            type="text"
            className="form-control"
            placeholder="Recipient's username"
            aria-label="Recipient's username"
            aria-describedby="basic-addon2"
            value={url}
          />
          <div className="ms-2 input-group-append">
            <button
              className="btn btn-primary"
              type="button"
              onClick={() => {
                navigator.clipboard.writeText(url);
              }}
            >
              Copy
            </button>
          </div>
        </div>
      </>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}

function OverloadTableForFeature(props: {
  tenant: string;
  feature: TLightFeature;
}) {
  const { project, name } = props.feature;
  const { tenant } = props;
  const contextQuery = useQuery({
    queryKey: [projectContextKey(tenant!, project!)],

    queryFn: () => queryContextsForProject(tenant!, project!),
  });

  const hasDeleteRight = useProjectRight(tenant, project, TProjectLevel.Write);
  const hasUpdateRight = useProjectRight(tenant, project, TProjectLevel.Update);

  if (contextQuery.isError) {
    return <div>Failed to fetch overloads</div>;
  } else if (contextQuery.data) {
    const overloads = findOverloadsForFeature(name, contextQuery.data);
    return (
      <OverloadTable
        contexts={contextQuery.data}
        overloads={overloads}
        project={project!}
        refresh={() =>
          queryClient.invalidateQueries({
            queryKey: [projectContextKey(tenant!, project!)],
          })
        }
        fields={["linkedPath", "enabled", "details"]}
        actions={() =>
          hasDeleteRight ? ["edit", "delete"] : hasUpdateRight ? ["edit"] : []
        }
      />
    );
  } else {
    return <Loader message="Loading..." />;
  }
}

function OverloadDetails(props: {
  feature: TLightFeature;
  cancel: () => void;
}) {
  const [creating, setCreating] = useState(false);
  const { tenant } = useParams();
  const { user, displayModal } = React.useContext(IzanamiContext);
  const canOverloadForGlobalProtectedContexts = hasRightForTenant(
    user!,
    tenant!,
    TLevel.Admin
  );
  const { feature, cancel } = props;
  const contextQuery = useQuery({
    queryKey: [projectContextKey(tenant!, feature.project!)],
    queryFn: () => queryContextsForProject(tenant!, feature.project!),
  });
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

    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: [projectContextKey(tenant!, feature.project!)],
      }),
  });
  const [selectedContext, selectContext] = useState<string>();
  const modificationRight = useProjectRight(
    tenant,
    feature.project!,
    TLevel.Write
  );

  if (contextQuery.error) {
    return <div>Failed to fetch contexts</div>;
  } else if (contextQuery.data) {
    const allContexts = possiblePaths(contextQuery.data);
    const excluded = findContextWithOverloadsForFeature(
      feature.name,
      contextQuery.data
    );

    return (
      <>
        <h4>
          Feature overloads{" "}
          {modificationRight && (
            <button
              className="btn btn-primary btn-sm mb-2 ms-3"
              type="button"
              onClick={() => setCreating(true)}
            >
              Create new overload
            </button>
          )}
        </h4>
        {creating && (
          <OverloadCreationForm
            resultType={feature.resultType}
            submit={(overload) => {
              const {
                impactedProtectedContexts,
                impactedRootProtectedContexts,
                unprotectedUpdateAllowed,
              } = analyzeUpdateImpact({
                contexts: allContexts.map((c) => c.context),
                project: overload.project!,
                tenant: tenant!,
                user: user!,
                updatedFeatureId: feature.id!,
                updatedContext: selectedContext,
              });

              if (impactedProtectedContexts.length > 0) {
                displayModal(({ close }) => {
                  return (
                    <OverloadUpdateConfirmationModal
                      hasUserAdminRightOnFeature={unprotectedUpdateAllowed}
                      impactedProtectedContexts={impactedProtectedContexts}
                      impactedRootProtectedContexts={
                        impactedRootProtectedContexts
                      }
                      context={selectedContext}
                      newFeature={overload as any}
                      onCancel={() => close()}
                      onConfirm={(strategyPreservation) => {
                        return updateStrategyMutation
                          .mutateAsync({
                            ...overload,
                            path: selectedContext as string,
                            feature: feature.name,
                            project: feature.project!,
                            strategyPreservation: strategyPreservation,
                          })
                          .then(() => setCreating(false));
                      }}
                    />
                  );
                });
              } else {
                updateStrategyMutation
                  .mutateAsync({
                    ...overload,
                    path: selectedContext as string,
                    feature: feature.name,
                    project: feature.project!,
                    strategyPreservation: false,
                  })
                  .then(() => setCreating(false));
              }
            }}
            project={feature.project!}
            cancel={() => setCreating(false)}
            defaultValue={feature as unknown as TContextOverload}
            noName
            additionalFields={() => (
              <label className="mt-3">
                Context
                <Select
                  styles={customStyles}
                  options={allContexts
                    .filter(({ path }) => !excluded.includes(path))
                    .map(({ path, context }) => {
                      return { path: path.substring(1), context: context };
                    })
                    .sort((c1, c2) => {
                      return c1.path.localeCompare(c2.path);
                    })
                    .map(({ path, context }) => {
                      let isAllowed = true;
                      if (context.global && context.protected) {
                        isAllowed = canOverloadForGlobalProtectedContexts!;
                      } else if (context.protected) {
                        isAllowed =
                          canOverloadForGlobalProtectedContexts ||
                          hasRightForProject(
                            user!,
                            TLevel.Admin,
                            context.project!,
                            tenant!
                          );
                      }

                      return {
                        isDisabled: !isAllowed,
                        value: path,
                        label: (
                          <span
                            style={{
                              display: "flex",
                              gap: "5px",
                              alignItems: "center",
                            }}
                          >
                            {path}
                            {context.global && <GlobalContextIcon />}
                            {context.protected && (
                              <i
                                className="fa-solid fa-lock fs-6 ml-5"
                                aria-label="protected"
                              ></i>
                            )}
                          </span>
                        ),
                      };
                    })}
                  isOptionDisabled={({ isDisabled }) => isDisabled}
                  onChange={(e) => selectContext(e?.value)}
                />
              </label>
            )}
          />
        )}
        <OverloadTableForFeature feature={feature} tenant={tenant!} />
        <div className="d-flex justify-content-end">
          <button
            type="button"
            className="btn btn-danger m-2"
            onClick={() => cancel()}
          >
            Close
          </button>
        </div>
      </>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}

export function FeatureDetails({ feature }: { feature: TLightFeature }) {
  return (
    <div className="d-flex">
      <div>
        <TextualFeatureDetails feature={feature} />
      </div>
    </div>
  );
}

function Pill({
  criticity,
  children,
  tooltip,
  onClick,
  ariaLabel,
}: {
  criticity: "info" | "warning" | "error";
  children: React.ReactNode;
  tooltip: string;
  onClick?: () => any;
  ariaLabel?: string;
}) {
  let borderStyle = "";
  if (criticity === "error") {
    borderStyle = "border-danger";
  } else if (criticity === "warning") {
    borderStyle = "border-warning";
  }
  const isButton = Boolean(onClick);

  return (
    <span
      role={isButton ? "button" : "generic"}
      aria-label={ariaLabel}
      className={`top-10 align-self-start badge rounded-pill bg-primary-outline ${borderStyle}`}
      style={{
        color: "var(--color-level-3)",
        marginTop: "-0.5rem",
        cursor: isButton ? "pointer" : "auto",
      }}
      data-tooltip-id="pill_tooltip"
      data-tooltip-content={tooltip}
      data-tooltip-place="top"
      onClick={onClick}
    >
      <Tooltip id="pill_tooltip" />
      {children}
    </span>
  );
}

export function FeatureTable(props: {
  features: TLightFeature[];
  fields: FeatureFields[];
  actions: (t: TLightFeature) => FeatureActionNames[];
  selectedSearchRow?: string;
  refresh: () => any;
}) {
  const { tenant } = useParams();
  const { fields, features, actions, refresh, selectedSearchRow } = props;
  const [selectedRows, setSelectedRows] = useState<TLightFeature[]>([]);

  const columns: ColumnDef<TLightFeature>[] = [];

  const { askConfirmation, displayModal, user, askInputConfirmation } =
    React.useContext(IzanamiContext);

  const featureUpdateMutation = useMutation({
    mutationFn: (data: {
      strategyPreservation: boolean;
      id: string;
      feature: Omit<TCompleteFeature, "stale" | "creationDate">;
    }) => {
      return updateFeature(
        tenant!,
        data.id,
        data.strategyPreservation,
        data.feature
      );
    },

    onSuccess: () => {
      refresh();
    },
  });

  const contextQueries = useQueries({
    queries: [...new Set(features.map((f) => f.project))].map((project) => {
      return {
        queryKey: [projectContextKey(tenant!, project!)],
        queryFn: () => queryContextsForProject(tenant!, project!),
        //queryFn: () => Promise((resolve, reject) => reject("err")),
        enabled: fields.includes("overloadCount"),
      };
    }),
  });

  const featureDeleteMutation = useMutation({
    mutationFn: (id: string) => deleteFeature(tenant!, id),

    onSuccess: () => {
      refresh();
    },
  });

  const featureCreateMutation = useMutation({
    mutationFn: (data: { project: string; feature: any }) =>
      createFeature(tenant!, data.project, data.feature),

    onSuccess: () => refresh(),
  });

  const staleMessage = (stale?: StaleStatus) => {
    switch (stale?.because) {
      case "NoCall":
        return `Feature has not been called since ${format(
          stale?.since,
          "PPPp"
        )}`;
      case "NeverCalled":
        return `Feature has never been called since its creation at ${format(
          stale?.since,
          "PPPp"
        )}`;
      case "NoValueChange":
        return `Feature value has not changed to its last value ${
          stale.value
        } since ${format(stale?.since, "PPPp")}`;
    }
    return "";
  };

  const displayFeature = (feature: TLightFeature) => {
    return (
      <>
        <span style={{ textOverflow: "ellipsis" }}>{feature.name}</span>
        {feature.stale && (
          <Pill
            criticity="warning"
            tooltip={staleMessage(feature.stale)}
            ariaLabel={`${feature.name} is stale`}
          >
            <i className="fa-solid fa-triangle-exclamation" aria-hidden></i>
          </Pill>
        )}
      </>
    );
  };

  if (fields.includes("name") && fields.includes("overloadCount")) {
    columns.push({
      accessorKey: "name",
      header: () => "Feature name",
      minSize: 150,
      size: 20,
      cell: (props: { row: Row<any> }) => {
        const feature: TLightFeature = props.row.original;
        const featureNameBase = displayFeature(feature);

        if (contextQueries.some((q) => q.isLoading)) {
          return (
            <div className="d-flex justify-start align-items-center">
              {featureNameBase}
            </div>
          );
        } else if (contextQueries.some((q) => q.isError)) {
          return (
            <div className="d-flex align-items-center">
              <div className="d-flex py-2">
                {featureNameBase}
                <Pill tooltip="Failed to fetch overloads" criticity={"error"}>
                  <i className="fa-solid fa-triangle-exclamation"></i>
                </Pill>
              </div>
            </div>
          );
        } else if (contextQueries.every((q) => q.isSuccess)) {
          const maybeContexts = contextQueries
            .map((q) => q.data)
            .filter((d) => Boolean(d))
            .map((d) => findOverloadsForFeature(feature.name, d as TContext[]))
            .filter((ctxs) => ctxs?.length > 0)
            .flat();

          if (!maybeContexts || maybeContexts.length === 0) {
            return (
              <div className="d-flex justify-start align-items-center">
                {featureNameBase}
              </div>
            );
          } else {
            return (
              <div className="d-flex align-items-center">
                <div className="d-flex py-2">
                  {featureNameBase}
                  <Pill
                    onClick={() =>
                      document
                        .getElementById(`overload-action-icon-${feature.id}`)
                        ?.click()
                    }
                    tooltip="Overloads"
                    criticity={"info"}
                    ariaLabel={`${maybeContexts.length} Overload${
                      maybeContexts.length > 1 ? "s" : ""
                    }`}
                  >
                    {maybeContexts.length}
                  </Pill>
                </div>
              </div>
            );
          }
        } else {
          return <></>;
        }
      },
    });
  } else if (fields.includes("name")) {
    columns.push({
      accessorKey: "name",
      header: () => "Feature name",
      cell: (props: { row: Row<any> }) => {
        const feature = props.row.original;
        return (
          <div className="d-flex justify-start align-items-center">
            {displayFeature(feature)}
          </div>
        );
      },
      minSize: 150,
      size: 20,
    });
  }
  if (fields.includes("enabled")) {
    columns.push({
      accessorKey: "enabled",
      header: () => "Status",
      cell: (info: any) => {
        const feature = info.row.original as TCompleteFeature;
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
                    const contexts = contextQueries.flatMap(
                      (q) => q.data ?? []
                    );
                    const {
                      impactedProtectedContexts,
                      impactedRootProtectedContexts,
                      unprotectedUpdateAllowed,
                    } = analyzeUpdateImpact({
                      updatedFeatureId: feature.id!,
                      tenant: tenant!,
                      project: feature.project!,
                      user: user!,
                      contexts: contexts,
                    });

                    if (impactedProtectedContexts.length > 0) {
                      displayModal(({ close }) => {
                        return (
                          <OverloadUpdateConfirmationModal
                            onCancel={() => close()}
                            onConfirm={(strategyPreservation) => {
                              return featureUpdateMutation.mutateAsync({
                                strategyPreservation,
                                id: feature.id!,
                                feature: {
                                  ...feature,
                                  enabled: !isEnabled,
                                },
                              });
                            }}
                            impactedProtectedContexts={
                              impactedProtectedContexts
                            }
                            impactedRootProtectedContexts={
                              impactedRootProtectedContexts
                            }
                            hasUserAdminRightOnFeature={
                              unprotectedUpdateAllowed
                            }
                            oldFeature={feature as TLightFeature}
                            newFeature={
                              {
                                ...feature,
                                enabled: !isEnabled,
                              } as any
                            }
                          />
                        );
                      });
                    } else {
                      featureUpdateMutation.mutateAsync({
                        strategyPreservation: false,
                        id: feature.id!,
                        feature: {
                          ...feature,
                          enabled: !isEnabled,
                        },
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
      cell: (props: any) => <FeatureDetails feature={props.row.original!} />,
    });
  }
  if (fields.includes("id")) {
    columns.push({
      accessorKey: "id",
      maxSize: 80,
      minSize: 60,
      size: 15,
      header: () => "ID",
      cell: (props: any) => {
        const value = props.row.original.id;

        return <CopyButton value={value} />;
      },
    });
  }
  if (fields.includes("tags")) {
    columns.push({
      accessorKey: "tags",
      id: "tags",
      header: () => "Tags",
      cell: (props: { row: Row<any> }) => {
        const feature = props.row.original!;
        return feature.tags.map((t: string) => (
          <div key={`${feature.id}-${t}`}>
            <Link to={`/tenants/${tenant}/tags/${t}`}>
              <span className="badge bg-warning text-dark">{`${t}`}</span>
            </Link>
          </div>
        ));
      },
      size: 10,
      minSize: 200,
      meta: {
        valueType: "discrete",
      },
      filterFn: (row: Row<any>, columnId: string, filterValue: any) => {
        if (!filterValue || filterValue?.length === 0) {
          return true;
        }
        const value: any = row.getValue(columnId);

        return filterValue.some((v: string) => value.includes(v));
      },
    });
  }
  if (fields.includes("project")) {
    columns.push({
      accessorKey: "project",
      header: () => "Project",
      minSize: 200,
      size: 10,
      meta: {
        valueType: "discrete",
      },
    });
  }

  const customActions: { [x: string]: TCustomAction<TLightFeature> } = {
    edit: {
      icon: (
        <>
          <i className="bi bi-pencil-square" aria-hidden></i> Edit
        </>
      ),
      hasRight: (user: TUser, feature: TLightFeature) => {
        return actions(feature).includes("edit");
      },
      customForm: (datum: TLightFeature, cancel: () => void) => {
        return (
          <>
            <h4>Edit feature</h4>
            <FeatureForm
              defaultValue={datum}
              submit={(feature) => {
                let hasConditionChanged = false;
                if ("conditions" in feature) {
                  if (!("conditions" in datum)) {
                    hasConditionChanged = true;
                  } else {
                    hasConditionChanged =
                      hasConditionChanged ||
                      !isEqual(feature.conditions, datum.conditions);
                  }
                } else if ("wasmConfig" in feature) {
                  if (!("wasmConfig" in datum)) {
                    hasConditionChanged = true;
                  } else {
                    hasConditionChanged =
                      hasConditionChanged ||
                      !isEqual(feature.wasmConfig, datum.wasmConfig);
                  }
                }

                if (feature.enabled !== datum.enabled) {
                  hasConditionChanged = true;
                } else if (feature.name !== datum.name) {
                  hasConditionChanged = true;
                } else if (feature.resultType !== datum.resultType) {
                  hasConditionChanged = true;
                }

                const callback = (strategyPreservation: boolean) =>
                  featureUpdateMutation.mutateAsync(
                    {
                      strategyPreservation,
                      id: datum.id!,
                      feature,
                    },
                    {
                      onSuccess: () => {
                        queryClient.invalidateQueries({
                          queryKey: [tagsQueryKey(tenant!)],
                        });
                        queryClient.invalidateQueries({
                          queryKey: [
                            projectContextKey(tenant!, datum.project!),
                          ],
                        });
                        cancel();
                      },
                    }
                  );

                if (hasConditionChanged) {
                  const contexts = contextQueries.flatMap((q) => q.data ?? []);

                  const contextsWithOverload = extractContextsMatching(
                    contexts,
                    (c) => c.overloads?.some((o) => o.id === datum.id),
                    false
                  );

                  const protectedContextsWithOverloads =
                    contextsWithOverload.filter((c) => c.protected);

                  const {
                    impactedProtectedContexts,
                    impactedRootProtectedContexts,
                    unprotectedUpdateAllowed,
                  } = analyzeUpdateImpact({
                    contexts: contexts,
                    project: feature.project!,
                    tenant: tenant!,
                    user: user!,
                    updatedFeatureId: feature.id!,
                  });

                  if (
                    contextsWithOverload.length > 0 &&
                    feature.resultType !== datum.resultType
                  ) {
                    if (
                      !unprotectedUpdateAllowed &&
                      protectedContextsWithOverloads.length > 0
                    ) {
                      const protectedContextNames =
                        protectedContextsWithOverloads.map((c) => {
                          const displayname = c.parent.concat(c.name).join("/");
                          return (
                            <span key={displayname}>
                              {displayname}&nbsp;
                              {c.global && (
                                <>
                                  <GlobalContextIcon />
                                  &nbsp;
                                </>
                              )}
                              {c.protected && (
                                <i
                                  className="fa-solid fa-lock fs-6"
                                  aria-label="protected"
                                ></i>
                              )}
                            </span>
                          );
                        });

                      askConfirmation(
                        <>
                          This feature has overloads for below protected
                          contexts with result type{" "}
                          <span className="fw-bold">{datum.resultType}</span>:
                          <ul>
                            {protectedContextNames.map((n, index) => (
                              <li key={index}>{n}</li>
                            ))}
                          </ul>
                          Updating feature result type to{" "}
                          <span className="fw-bold">{feature.resultType}</span>
                          will delete these overloads.
                          <br />
                          You are not allowed to delete these overload since
                          only a project admin can update protected contexts,
                          therefore&nbsp;
                          <span className="fw-bold">
                            you are not allowed to perform this operation
                          </span>
                          .
                        </>
                      );
                    } else {
                      return askConfirmation(
                        <>
                          This feature has overload for below contexts with{" "}
                          <span className="fw-bold">{datum.resultType}</span>{" "}
                          result type:
                          <ul>
                            {contextsWithOverload.map((c) => {
                              const displayname = c.parent
                                .concat(c.name)
                                .join("/");

                              return (
                                <li key={displayname}>
                                  <span>
                                    {displayname}&nbsp;
                                    {c.global && (
                                      <>
                                        <GlobalContextIcon />
                                        &nbsp;
                                      </>
                                    )}
                                    {c.protected && (
                                      <i
                                        className="fa-solid fa-lock fs-6"
                                        aria-label="protected"
                                      ></i>
                                    )}
                                  </span>
                                </li>
                              );
                            })}
                          </ul>
                          Updating result type to{" "}
                          <span className="fw-bold">{feature.resultType}</span>{" "}
                          will delete all these overloads, are you sure that it
                          is what you want ?
                        </>,
                        () => callback(false)
                      );
                    }
                  } else if (impactedRootProtectedContexts.length > 0) {
                    return displayModal(({ close }) => {
                      return (
                        <OverloadUpdateConfirmationModal
                          impactedProtectedContexts={impactedProtectedContexts}
                          impactedRootProtectedContexts={
                            impactedRootProtectedContexts
                          }
                          hasUserAdminRightOnFeature={unprotectedUpdateAllowed}
                          oldFeature={datum as TLightFeature}
                          newFeature={feature as TLightFeature}
                          onCancel={() => close()}
                          onConfirm={(strategyPreservation) =>
                            callback(strategyPreservation)
                          }
                        />
                      );
                    });
                  } else {
                    return callback(false);
                  }
                } else {
                  return callback(false);
                }
              }}
              cancel={cancel}
            />
          </>
        );
      },
    },
    overloads: {
      icon: (feature: TLightFeature) => {
        return (
          <>
            <i
              id={`overload-action-icon-${feature.id}`}
              className="fa-solid fa-filter"
              aria-hidden
            ></i>
            &nbsp;Overloads
          </>
        );
      },
      hasRight: (user: TUser, feature: TLightFeature) => {
        return actions(feature).includes("overloads");
      },
      customForm: (datum: TLightFeature, cancel: () => void) => {
        return <OverloadDetails feature={datum} cancel={cancel} />;
      },
    },
    test: {
      icon: (
        <>
          <i className="bi bi-wrench" aria-hidden></i> Test feature
        </>
      ),
      hasRight: (user: TUser, feature: TLightFeature) => {
        return actions(feature).includes("test");
      },
      customForm: (datum: TLightFeature, cancel: () => void) => {
        return <ExistingFeatureTestForm feature={datum} cancel={cancel} />;
      },
    },
    duplicate: {
      icon: (
        <>
          <i className="bi bi-clipboard" aria-hidden></i> Duplicate
        </>
      ),
      hasRight: (user: TUser, feature: TLightFeature) => {
        return actions(feature).includes("duplicate");
      },
      customForm: (datum: TLightFeature, cancel: () => void) => {
        return (
          <div className="anim__rightToLeft">
            <h4>Duplicate feature</h4>
            <FeatureForm
              defaultValue={datum}
              submit={(feature) =>
                featureCreateMutation
                  .mutateAsync({
                    feature: { ...feature, id: undefined },
                    project: datum.project!,
                  })
                  .then(() => cancel())
              }
              cancel={cancel}
            />
          </div>
        );
      },
    },
    transfer: {
      icon: (
        <>
          <i className="fa-solid fa-arrow-right-arrow-left" aria-hidden></i>{" "}
          Transfer
        </>
      ),
      hasRight: (user: TUser, feature: TLightFeature) => {
        return actions(feature).includes("transfer");
      },
      customForm: (datum: TLightFeature, cancel: () => void) => {
        return (
          <div className="anim__rightToLeft">
            <h4>Transfer to another project</h4>
            <TransferForm
              project={datum.project!}
              tenant={tenant!}
              feature={datum}
              cancel={cancel}
              validationCallback={([feature]) => {
                const contexts = contextQueries.flatMap(
                  (ctxq) => ctxq.data ?? []
                );
                const protectedOverloads = findOverloadsForFeature(
                  feature.name,
                  contexts,
                  (ctx) => ctx.protected
                );
                if (
                  !hasRightForProject(
                    user!,
                    TLevel.Admin,
                    feature.project!,
                    tenant!
                  )
                ) {
                  return askConfirmation(
                    <>
                      <h3>Operation not permitted</h3>
                      <div>
                        Feature {feature.name} have overload(s) in below
                        protected context(s)
                      </div>
                      <ul>
                        {protectedOverloads.map((o) => (
                          <li key={o.path}>{o.path}</li>
                        ))}
                      </ul>
                      <p>
                        Transferring this feature would delete these overloads.
                        You don't have admin right on this project,{" "}
                        <span className="fw-bold">
                          therefore you can't transfer this feature.
                        </span>
                      </p>
                    </>
                  );
                }

                return askInputConfirmation(
                  <>
                    <h3>Transfer confirmation</h3>
                    <div>
                      Feature <span className="fw-bold">{feature.name}</span>{" "}
                      have overload(s) in below protected context(s)
                    </div>
                    <ul>
                      {protectedOverloads.map((o) => (
                        <li key={o.path}>{o.path}</li>
                      ))}
                    </ul>
                    <p>
                      Transferring {feature.name} will delete{" "}
                      <span className="fw-bold">all associated overloads</span>.
                    </p>
                    Type feature name below to confirm.
                  </>,
                  () => featureDeleteMutation.mutateAsync(feature.id!),
                  feature.name
                );
              }}
            />
          </div>
        );
      },
    },
    delete: {
      icon: (
        <>
          <i className="bi bi-trash" aria-hidden></i> Delete
        </>
      ),
      hasRight: (user: TUser, feature: TLightFeature) => {
        return actions(feature).includes("delete");
      },
      action: (feature: TLightFeature) => {
        const contexts = contextQueries.flatMap((ctxq) => ctxq.data ?? []);
        const protectedOverloads = findOverloadsForFeature(
          feature.name,
          contexts,
          (ctx) => ctx.protected
        );

        const { impactedProtectedContexts, unprotectedUpdateAllowed } =
          analyzeUpdateImpact({
            tenant: tenant!,
            project: feature.project!,
            user: user!,
            contexts: contexts,
            updatedFeatureId: feature.id!,
          });

        const isProjectAdmin = hasRightForProject(
          user!,
          TProjectLevel.Admin,
          feature.project!,
          tenant!
        );
        if (impactedProtectedContexts.length > 0 && !unprotectedUpdateAllowed) {
          return askConfirmation(
            <>
              <h3>Operation not permitted</h3>
              <div>
                Deleting feature <span className="fw-bold">{feature.name}</span>{" "}
                would impact below protected contexts:
                <ul>
                  {impactedProtectedContexts.map((ctx) => {
                    return <li key={ctx.name}>{ctx.name}</li>;
                  })}
                </ul>
              </div>
              <p>
                You don't have admin rights on this project, therefore you can't
                delete{" "}
                {impactedProtectedContexts.length > 1
                  ? "these features"
                  : "this feature"}
                .
              </p>
            </>
          );
        } else if (protectedOverloads.length > 0 && isProjectAdmin) {
          return askInputConfirmation(
            <>
              <h3>Delete confirmation</h3>
              <div>
                Feature <span className="fw-bold">{feature.name}</span> have
                overload(s) in below protected context(s)
              </div>
              <ul>
                {protectedOverloads.map((o) => (
                  <li key={o.path}>{o.path}</li>
                ))}
              </ul>
              <p>
                Deleting {feature.name} will delete{" "}
                <span className="fw-bold">all associated overloads</span>.
              </p>
              Type feature name below to confirm.
            </>,
            () => featureDeleteMutation.mutateAsync(feature.id!),
            feature.name
          );
        } else if (protectedOverloads.length > 0 && !isProjectAdmin) {
          return askConfirmation(
            <>
              <h3>Delete confirmation</h3>
              Feature <span className="fw-bold">{feature.name}</span> have
              overloads for below protected contexts:
              <ul>
                {protectedOverloads
                  .map((o) => o.name)
                  .map((name) => (
                    <li key={name}>{name}</li>
                  ))}
              </ul>
              <p>
                Updating protected overloads require admin rights on project.
              </p>
              <p>
                You don't have admin rights on this project, therefore you can't
                delete this feature.
              </p>
            </>
          );
        } else {
          return askConfirmation(
            <>
              <h3>Delete confirmation</h3>
              Are you sure you want to delete feature{" "}
              <span className="fw-bold">{feature.name}</span> ?
            </>,
            () => featureDeleteMutation.mutateAsync(feature.id!)
          );
        }
      },
    },
    url: {
      icon: (
        <>
          <i className="bi bi-link-45deg" aria-hidden></i> Url
        </>
      ),
      hasRight: (user: TUser, feature: TLightFeature) => {
        return actions(feature).includes("url");
      },
      action: (feature: TLightFeature) =>
        askConfirmation(
          <FeatureUrl tenant={tenant!} feature={feature} />,
          () => Promise.resolve(),
          () => Promise.resolve(),
          "Close"
        ),
    },
  };

  const [bulkOperation, setBulkOperation] = useState<string | undefined>(
    undefined
  );

  const selectedFeatures = features.filter((f) =>
    selectedRows.map((item) => item.id).includes(f.id)
  );

  const canDeleteSelected = selectedRows
    .map((f) => f.project!)
    .every((p) => hasRightForProject(user!, TProjectLevel.Write, p, tenant!));

  const selectableRows = features
    .map((f) => f.project!)
    .some((p) => hasRightForProject(user!, TProjectLevel.Update, p, tenant!));
  return (
    <div>
      {selectableRows && (
        <div
          className={`d-flex align-items-center ${
            selectedRows.length > 0 ? "" : "invisible"
          }`}
        >
          <Select
            options={(canDeleteSelected
              ? BULK_OPERATIONS
              : UPDATE_BULK_OPERATIONS
            ).map((op) => ({ label: op, value: op }))}
            value={
              bulkOperation
                ? {
                    label: bulkOperation,
                    value: bulkOperation,
                  }
                : null
            }
            onChange={(e) => {
              if (e?.value) {
                setBulkOperation(e.value);
              } else {
                setBulkOperation(undefined);
              }
            }}
            styles={customStyles}
            isClearable={true}
            isDisabled={selectedRows?.length === 0}
            placeholder="Bulk action"
            aria-label="Bulk action"
          />
          &nbsp;
          {bulkOperation && (
            <OperationForm
              tenant={tenant!}
              bulkOperation={bulkOperation}
              selectedRows={selectedFeatures}
              cancel={() => setBulkOperation(undefined)}
              refresh={() => refresh()}
              validationCallback={(action, features) => {
                const contexts = contextQueries.flatMap((q) => q.data ?? []);
                const protectedContexts = extractContextsMatching(
                  contexts,
                  (ctx) => ctx.protected
                );

                const protectedOverloadsByProjectAndFeatures = protectedContexts
                  .flatMap((ctx) =>
                    ctx.overloads
                      .map((o) => {
                        return {
                          id: o.id,
                          feature: o.name,
                          path: ctx.parent.concat(ctx.name),
                          project: o.project,
                        };
                      })
                      .filter(({ id }) =>
                        features.map((f) => f.id).includes(id)
                      )
                  )
                  .reduce((acc, { feature, path, project }) => {
                    if (!acc.has(project!)) {
                      const newEntry = new Map();
                      newEntry.set(feature, [path]);
                      acc.set(project!, newEntry);
                    } else {
                      const projectEntry = acc.get(project!)!;
                      if (!projectEntry.has(feature)) {
                        const newEntry = new Map();
                        newEntry.set(feature, [path]);
                      } else {
                        projectEntry.get(feature)!.push(path.join("/"));
                      }
                    }
                    return acc;
                  }, new Map<string, Map<string, string[]>>());

                const forbiddenOverloadToDelete =
                  protectedOverloadsByProjectAndFeatures
                    .entries()
                    .filter(
                      ([project]) =>
                        !hasRightForProject(
                          user!,
                          TLevel.Admin,
                          project,
                          tenant!
                        )
                    )
                    .reduce((acc, [project, overloadByFeature]) => {
                      acc.set(project, overloadByFeature);
                      return acc;
                    }, new Map<string, Map<string, string[]>>());

                if (
                  (bulkOperation === "Delete" ||
                    bulkOperation === "Transfer") &&
                  forbiddenOverloadToDelete.size > 0
                ) {
                  return askConfirmation(
                    <div
                      style={{
                        padding: "0.5rem",
                      }}
                    >
                      {bulkOperation === "Delete" ? "Deleting" : "Transfering"}{" "}
                      these features would cause deletion of overload for the
                      following protected contexts :
                      <ul>
                        {forbiddenOverloadToDelete
                          .entries()
                          .flatMap(([project, pathByFeatures]) =>
                            pathByFeatures
                              .entries()
                              .flatMap(([feature, pathes]) => {
                                return pathes.map((path) => [
                                  project,
                                  feature,
                                  path,
                                ]);
                              })
                              .map(([project, feature, path]) => {
                                return (
                                  <li key={`${project}-${feature}-${path}`}>
                                    <i className="fas fa-building" />
                                    &nbsp;{project} {">"}{" "}
                                    <i className="fas fa-rocket" />
                                    &nbsp;{feature} {">"} <GlobalContextIcon />
                                    &nbsp;{path}
                                  </li>
                                );
                              })
                          )}
                      </ul>
                      You don't have admin right on these projects and therefore
                      cannot delete these overloards.
                    </div>
                  );
                } else if (
                  bulkOperation === "Enable" ||
                  bulkOperation === "Disable"
                ) {
                  const impactByFeature = features
                    .filter((feat) => {
                      return (
                        (feat.enabled && bulkOperation === "Disable") ||
                        (!feat.enabled && bulkOperation === "Enable")
                      );
                    })
                    .reduce(
                      (acc, f) => {
                        const impact = analyzeUpdateImpact({
                          updatedFeatureId: f.id!,
                          project: f.project!,
                          contexts: contexts,
                          tenant: tenant!,
                          user: user!,
                        });

                        const enabling =
                          bulkOperation === "Enable" ? true : false;
                        acc[f.id!] = {
                          ...impact,
                          newFeature: {
                            ...f,
                            enabled: enabling,
                          } as TLightFeature,
                          oldFeature: f,
                          hasUserAdminRightOnFeature:
                            impact.unprotectedUpdateAllowed,
                        };

                        return acc;
                      },
                      {} as {
                        [x: string]: ImpactAnalysisResult & {
                          newFeature: TLightFeature;
                          oldFeature: TLightFeature;
                          hasUserAdminRightOnFeature: boolean;
                        };
                      }
                    );
                  const hasProtectedContextImpact = Object.values(
                    impactByFeature
                  ).some((i) => i.impactedProtectedContexts.length > 0);
                  if (hasProtectedContextImpact) {
                    let ok = false; // FIXME this is ugly
                    return displayModal(({ close }) => (
                      <MultiFeatureOverloadUpdateConfirmationModal
                        features={impactByFeature}
                        onCancel={() => close()}
                        onConfirm={() => {
                          ok = true;
                          close();
                          return Promise.resolve();
                        }}
                      />
                    )).then(() => {
                      if (!ok) {
                        throw new Error("");
                      }
                    });
                  } else {
                    return Promise.resolve();
                  }
                } else {
                  return Promise.resolve();
                }
              }}
            />
          )}
        </div>
      )}
      <GenericTable
        selectableRows={selectableRows}
        idAccessor={(datum: TLightFeature | TContextOverload) => {
          return datum.name;
        }}
        columns={columns}
        data={features}
        customRowActions={customActions}
        defaultSort="name"
        onRowSelectionChange={(rows) => {
          setSelectedRows(rows);
        }}
        isRowSelectable={(feature) =>
          hasRightForProject(user!, TLevel.Read, feature.project!, tenant!)
        }
        filters={
          selectedSearchRow ? [{ id: "name", value: selectedSearchRow }] : []
        }
      />
    </div>
  );
}
