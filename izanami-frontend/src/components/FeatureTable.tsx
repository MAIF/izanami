import * as React from "react";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  ClassicalFeature,
  DAYS,
  isPercentageRule,
  isSingleConditionFeature,
  isUserListRule,
  TCondition,
  TContext,
  TContextOverload,
  TDayOfWeepPeriod,
  TFeaturePeriod,
  TFeatureRule,
  THourPeriod,
  TLevel,
  TLightFeature,
  TUser,
  TWasmConfig,
  TCompleteFeature,
} from "../utils/types";
import { format, parse } from "date-fns";
import { useMutation, useQueries, useQuery } from "react-query";
import {
  createFeature,
  deleteFeature,
  deleteFeatureActivationForContext,
  patchFeatures,
  projectContextKey,
  projectQueryKey,
  queryContextsForProject,
  queryTenant,
  tagsQueryKey,
  tenantQueryKey,
  testExistingFeature,
  testFeature,
  updateFeature,
  queryTags,
  updateFeatureActivationForContext,
} from "../utils/queries";
import {
  IzanamiContext,
  hasRightForProject,
  useProjectRight,
} from "../securityContext";
import { GenericTable, TCustomAction } from "./GenericTable";
import { ColumnDef, Row } from "@tanstack/react-table";
import queryClient from "../queryClient";
import {
  FeatureForm,
  LegacyFeature,
  toLegacyFeatureFormat,
} from "./FeatureForm";
import { OverloadCreationForm } from "./OverloadCreationForm";
import { useForm, Controller } from "react-hook-form";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { Tooltip } from "react-tooltip";
import { Form } from "../components/Form";
import { Loader } from "./Loader";
import MultiSelect, { Option } from "./MultiSelect";
import { constraints } from "@maif/react-forms";

type FeatureFields =
  | "id"
  | "name"
  | "tags"
  | "enabled"
  | "details"
  | "project"
  | "overloadCount";

type OverloadFields = "name" | "enabled" | "details" | "path" | "linkedPath";

type OverloadActionNames = "delete" | "edit" | "test";

type FeatureActionNames =
  | "delete"
  | "edit"
  | "test"
  | "overloads"
  | "duplicate"
  | "transfer"
  | "url";
interface FeatureTestType {
  user?: string;
  date: Date;
  context?: string;
}
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

export function isAString(variable: any) {
  return typeof variable === "string" || variable instanceof String;
}

function days(days: TDayOfWeepPeriod): string {
  return `on ${days.days
    .sort((d1, d2) => {
      const index1 = DAYS.indexOf(d1);
      const index2 = DAYS.indexOf(d2);

      return index1 - index2;
    })
    .join(", ")}`;
}

function formatHours(hours: string): string {
  return hours;
}

function hours(hours: THourPeriod): string {
  return `from ${formatHours(hours.startTime)} to ${formatHours(
    hours.endTime
  )}`;
}

function PeriodDetails(props: { period: TFeaturePeriod }): JSX.Element {
  const { period } = props;
  if (period.activationDays || period.hourPeriods.length > 0) {
    return (
      <>
        {period.activationDays && period.activationDays.days.length != 7 && (
          <div>{days(period.activationDays)}</div>
        )}
        {period.hourPeriods.map((hour) => {
          const hourDisplay = hours(hour);
          return <div key={hourDisplay}>{hourDisplay}</div>;
        })}
      </>
    );
  } else {
    return <></>;
  }
}

function Period({ period }: { period: TFeaturePeriod }): JSX.Element {
  let display = "";
  if (period.begin && period.end) {
    display = `from ${format(period.begin, "PPP")} to ${format(
      period.end,
      "PPP"
    )}`;
  } else if (period.begin) {
    display = `after ${format(period.begin, "PPP")}`;
  } else if (period.end) {
    display = `until ${format(period.end, "PPP")}`;
  }
  return (
    <>
      <div>{display}</div>
      <PeriodDetails period={period} />
    </>
  );
}

function Rule(props: { rule: TFeatureRule }): JSX.Element {
  const { rule } = props;
  if (isPercentageRule(rule)) {
    return <>For {`${rule.percentage}% of users`}</>;
  } else if (isUserListRule(rule)) {
    return <>{`Only for : ${rule.users.join(", ")}`}</>;
  } else {
    return <>For all users</>;
  }
}
export function possiblePaths(contexts: TContext[], path = ""): string[] {
  return contexts.flatMap((ctx) => {
    if (ctx.children) {
      return [
        ...possiblePaths(ctx.children, path + "/" + ctx.name),
        path + "/" + ctx.name,
      ];
    } else {
      return [];
    }
  });
}

function ScriptDetails({ config }: { config: string }) {
  return <div>Depends on script {config}</div>;
}

function SingleConditionFeatureDetail({ feature }: { feature: LegacyFeature }) {
  switch (feature.activationStrategy) {
    case "PERCENTAGE":
      return <>For {feature.parameters.percentage}% of users</>;
    case "CUSTOMERS_LIST":
      return <>Only for : {feature.parameters.customers.join(", ")}</>;
    case "NO_STRATEGY":
      return <>For all users</>;
    case "DATE_RANGE":
      return (
        <>
          from {format(feature.parameters.from, "PPP")} to{" "}
          {format(feature.parameters.to, "PPP")}
        </>
      );
    case "HOUR_RANGE":
      return (
        <>
          from {formatHours(feature.parameters.startAt)} to{" "}
          {formatHours(feature.parameters.endAt)}
        </>
      );
    case "RELEASE_DATE":
      return <>from {format(feature.parameters.date, "PPP")}</>;
    default:
      return <></>;
  }
}

function ConditionDetails({ conditions }: { conditions: TCondition[] }) {
  if (conditions.length === 0) {
    return (
      <>
        <div className="fw-semibold">Active : </div>For all users
      </>
    );
  }
  return (
    <>
      {conditions.map(({ period, rule }, index, array) => {
        if (index === array.length - 1) {
          return (
            <React.Fragment key={index}>
              {(period || rule) && <div className="fw-semibold">Active : </div>}
              {period && <Period period={period} />}
              {rule && <Rule rule={rule} />}
            </React.Fragment>
          );
        } else {
          return (
            <React.Fragment key={index}>
              {(period || rule) && <div className="fw-semibold">Active : </div>}
              {period && <Period period={period} />}
              {rule && <Rule rule={rule} />}
              <div className="feature-separator">-OR-</div>
            </React.Fragment>
          );
        }
      })}
    </>
  );
}

function findOverloadsForFeature(
  name: string,
  contexts: TContext[],
  path: string[] = []
): TContextOverload[] {
  return contexts.flatMap((ctx) => {
    const maybeOverload = ctx.overloads
      .filter((o) => o.name === name)
      .map((o) => ({ ...o, path: [...path, ctx.name].join("/") }));
    const childOverloads = findOverloadsForFeature(name, ctx.children, [
      ...path,
      ctx.name,
    ]);

    if (maybeOverload) {
      return [...maybeOverload, ...childOverloads];
    } else {
      return childOverloads;
    }
  });
}

function findContextWithOverloadsForFeature(
  name: string,
  contexts: TContext[],
  path = ""
): string[] {
  return contexts.flatMap((ctx) => {
    const hasOverload = ctx.overloads.some((o) => o.name === name);
    const childOverloadsCtx = findContextWithOverloadsForFeature(
      name,
      ctx.children,
      path + "/" + ctx.name
    );

    if (hasOverload) {
      return [path + "/" + ctx.name, ...childOverloadsCtx];
    } else {
      return childOverloadsCtx;
    }
  });
}

function OperationButton(props: {
  tenant: string;
  bulkOperation: string;
  selectedRows: TLightFeature[];
  cancel: () => void;
  refresh: () => any;
}) {
  const { tenant, bulkOperation, selectedRows, cancel, refresh } = props;
  const hasSelectedRows = selectedRows.length > 0;
  const { askConfirmation } = React.useContext(IzanamiContext);
  return (
    <>
      <button
        className="ms-2 btn btn-primary"
        type="button"
        disabled={!hasSelectedRows || !bulkOperation}
        onClick={() => {
          switch (bulkOperation) {
            case "Delete":
              askConfirmation(
                `Are you sure you want to delete ${
                  selectedRows.length
                } feature${selectedRows.length > 1 ? "s" : ""} ?`,
                () => {
                  return patchFeatures(
                    tenant!,
                    selectedRows.map((f) => ({
                      op: "remove",
                      path: `/${f.id}`,
                    }))
                  ).then(() => refresh());
                }
              );
              break;
            case "Enable":
              patchFeatures(
                tenant!,
                selectedRows.map((f) => ({
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
                selectedRows.map((f) => ({
                  op: "replace",
                  path: `/${f.id}/enabled`,
                  value: false,
                }))
              )
                .then(() => refresh())
                .then(() => cancel());
              break;
          }
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
}) {
  const { tenant, selectedRows, cancel, refresh } = props;
  const selectedRowProjects = selectedRows.map((f) => f.project);
  const selectedRowProject = selectedRowProjects.filter(
    (q, idx) => selectedRowProjects.indexOf(q) === idx
  );

  const projectQuery = useQuery(tenantQueryKey(tenant), () =>
    queryTenant(tenant)
  );
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
            props: { styles: customStyles },
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
          return askConfirmation(
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
          );
        }}
        footer={({ valid }: { valid: () => void }) => {
          return (
            <div className="d-flex justify-content-end">
              <button
                type="button"
                className="btn btn-danger m-2"
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
}) {
  const { tenant, selectedRows, cancel, refresh } = props;
  const tagsQuery = useQuery(tagsQueryKey(tenant), () => queryTags(tenant));
  const selectedRowTags = [...new Set(selectedRows.flatMap((f) => f.tags))];
  const { askConfirmation } = React.useContext(IzanamiContext);
  const [values, setSelectedValues] = React.useState<Option[] | null>();

  const onSelected = (selectedOptions: Option[]) => {
    setSelectedValues(selectedOptions);
  };
  const OnSubmit = (selectedRows: TLightFeature[], values: Option[]) => {
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
}) {
  const { project, tenant, feature, cancel } = props;
  const projectQuery = useQuery(tenantQueryKey(tenant), () =>
    queryTenant(tenant)
  );

  const featureUpdateMutation = useMutation((data: { project: string }) =>
    patchFeatures(tenant, [
      {
        op: "replace",
        path: `/${feature.id}/project`,
        value: data.project,
      },
    ]).then(() => {
      queryClient.invalidateQueries(projectQueryKey(tenant, project));
    })
  );

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
          return askConfirmation(
            "Transferring this feature will delete existing local overloads (if any), are you sure ?",
            () => featureUpdateMutation.mutateAsync(data as any)
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
}) {
  const { tenant, bulkOperation, selectedRows, cancel, refresh } = props;
  switch (bulkOperation) {
    case "Transfer":
      return (
        <OperationTransferForm
          tenant={tenant!}
          selectedRows={selectedRows}
          cancel={cancel}
          refresh={refresh}
        />
      );
    case "Apply Tags":
      return (
        <OperationTagForm
          tenant={tenant!}
          selectedRows={selectedRows}
          cancel={cancel}
          refresh={refresh}
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
        />
      );
  }
}

export function CopyButton(props: { value: any; title?: any }) {
  const [validCheckMark, setValidCheckmark] = React.useState<boolean>(false);
  const [idCheckMark, setIdCheckmark] = React.useState<number>();
  const timeRef = React.useRef<NodeJS.Timeout>();
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
              className="bi bi-clipboard me-2"
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
          <i className="bi bi-clipboard me-2" aria-label="copy feature id"></i>
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
  const contextQuery = useQuery(projectContextKey(tenant!, project!), () =>
    queryContextsForProject(tenant!, project!)
  );
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
    const allContexts = possiblePaths(contextQuery.data);

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
  const contextQuery = useQuery(projectContextKey(tenant!, project!), () =>
    queryContextsForProject(tenant!, project!)
  );

  const hasModificationRight = useProjectRight(tenant, project, TLevel.Write);

  if (contextQuery.isError) {
    return <div>Failed to fetch overloads</div>;
  } else if (contextQuery.data) {
    const overloads = findOverloadsForFeature(name, contextQuery.data);
    return (
      <OverloadTable
        overloads={overloads}
        project={project!}
        refresh={() =>
          queryClient.invalidateQueries(projectContextKey(tenant!, project!))
        }
        fields={["linkedPath", "name", "enabled", "details"]}
        actions={() => (hasModificationRight ? ["edit", "delete"] : [])}
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
  const { feature, cancel } = props;
  const contextQuery = useQuery(
    projectContextKey(tenant!, feature.project!),
    () => queryContextsForProject(tenant!, feature.project!)
  );
  const updateStrategyMutation = useMutation(
    (data: {
      enabled: boolean;
      conditions?: TCondition[];
      feature: string;
      path: string;
      wasm?: TWasmConfig;
      project: string;
    }) => {
      return updateFeatureActivationForContext(
        tenant!,
        data.project,
        data.path,
        data.feature,
        data.enabled,
        data.conditions,
        data.wasm
      );
    },
    {
      onSuccess: () =>
        queryClient.invalidateQueries(
          projectContextKey(tenant!, feature.project!)
        ),
    }
  );
  const [selectedContext, selectContext] = useState<string>();

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
          <button
            className="btn btn-primary btn-sm mb-2 ms-3"
            type="button"
            onClick={() => setCreating(true)}
          >
            Create new overload
          </button>
        </h4>
        {creating && (
          <OverloadCreationForm
            submit={(overload) =>
              updateStrategyMutation
                .mutateAsync({
                  enabled: overload.enabled,
                  conditions: overload.conditions,
                  feature: feature.name,
                  path: selectedContext as string,
                  wasm: overload.wasmConfig,
                  project: feature.project!,
                })
                .then(() => setCreating(false))
            }
            project={feature.project!}
            cancel={() => setCreating(false)}
            defaultValue={feature as TContextOverload}
            noName
            additionalFields={() => (
              <label className="mt-3">
                Context
                <Select
                  styles={customStyles}
                  options={allContexts
                    .filter((ctx) => !excluded.includes(ctx))
                    .map((c) => c.substring(1))
                    .sort()
                    .map((c) => ({ value: c, label: c }))}
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
function ExistingFeatureTestForm(props: {
  feature: TLightFeature | TContextOverload;
  cancel?: () => any;
  context?: string;
}) {
  const { control, register, handleSubmit } = useForm<FeatureTestType>();
  const { feature } = props;

  const [message, setMessage] = useState<string | undefined>(undefined);
  const { tenant } = useParams();

  const contextQuery = useQuery(
    projectContextKey(tenant!, feature.project!),
    () => queryContextsForProject(tenant!, feature.project!)
  );

  const featureTestMutation = useMutation(
    ({ context, user, date }: { date: Date; user: string; context: string }) =>
      testExistingFeature(tenant!, feature.id!, date, context ?? "", user ?? "")
  );

  if (contextQuery.error) {
    return <div>Error while fetching contexts</div>;
  } else if (contextQuery.data) {
    const allContexts = possiblePaths(contextQuery.data);
    return (
      <form
        onSubmit={handleSubmit(({ user, date, context }) => {
          setMessage("");
          featureTestMutation
            .mutateAsync({
              context: context ?? "",
              user: user ?? "",
              date,
            })
            .then(({ active }) => {
              setMessage(
                `${feature.name} would be ${
                  active ? "active" : "inactive"
                } on ${format(date, "yyyy-MM-dd")}${
                  user ? ` for user ${user}` : ""
                }${context ? ` for context ${context}` : ""}`
              );
            });
        })}
        className="d-flex flex-column"
      >
        <label className="mt-3">
          Context
          <Controller
            name={`context`}
            control={control}
            defaultValue={props.context ?? ""}
            render={({ field: { onChange, value } }) => (
              <Select
                value={{ label: value, value }}
                onChange={(e) => {
                  onChange(e?.value);
                }}
                styles={customStyles}
                options={allContexts
                  .map((c) => c.substring(1))
                  .sort()
                  .map((c) => ({ value: c, label: c }))}
                isClearable
              />
            )}
          />
        </label>
        <label className="mt-3 col-2">
          Date
          <Controller
            name="date"
            defaultValue={new Date()}
            control={control}
            render={({ field: { onChange, value } }) => {
              return (
                <input
                  className="form-control"
                  defaultValue={format(new Date(), "yyyy-MM-dd")}
                  value={value ? format(value, "yyyy-MM-dd") : ""}
                  onChange={(e) => {
                    onChange(parse(e.target.value, "yyyy-MM-dd", new Date()));
                  }}
                  type="date"
                />
              );
            }}
          />
        </label>
        <label className="mt-3">
          User
          <input
            type="text"
            className="form-control"
            {...register("user")}
          ></input>
        </label>
        {message && (
          <div className="d-flex justify-content-end">
            <output className="m-2 py-1 px-3 anim__highlighter">
              {message}
            </output>
          </div>
        )}
        <div className="d-flex justify-content-end">
          {props.cancel && (
            <button
              type="button"
              className="btn btn-danger m-2"
              onClick={() => props.cancel?.()}
            >
              Cancel
            </button>
          )}
          <button type="submit" className="btn btn-success m-2">
            Test feature
          </button>
        </div>
      </form>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}

export function OverloadTable(props: {
  project?: string;
  overloads: TContextOverload[];
  fields: OverloadFields[];
  actions: (t: TContextOverload) => OverloadActionNames[];
  refresh: () => any;
}) {
  const { tenant } = useParams();
  const { fields, overloads, actions, refresh, project } = props;
  const columns: ColumnDef<TContextOverload>[] = [];
  const updateStrategyMutation = useMutation(
    (data: {
      enabled: boolean;
      conditions?: TCondition[];
      feature: string;
      path: string;
      wasm?: TWasmConfig;
      project: string;
    }) => {
      return updateFeatureActivationForContext(
        tenant!,
        data.project,
        data.path,
        data.feature,
        data.enabled,
        data.conditions,
        data.wasm
      );
    },
    {
      onSuccess: () => {
        refresh();
      },
    }
  );

  const deleteStrategyMutation = useMutation(
    (data: { feature: string; path: string; project: string }) =>
      deleteFeatureActivationForContext(
        tenant!,
        data.project, // TODO this should not be necessary
        data.path,
        data.feature
      ),
    {
      onSuccess: () => {
        refresh();
      },
    }
  );

  const { askConfirmation } = React.useContext(IzanamiContext);

  const hasPath = fields.includes("path");
  const hasLinkedPath = fields.includes("linkedPath");
  if (hasPath || hasLinkedPath) {
    columns.push({
      accessorKey: "path",
      header: () => "Overload path",
      cell: (info: any) =>
        hasLinkedPath ? (
          <a
            href={`/tenants/${tenant}/projects/${project}/contexts?open=["${info.getValue()}"]`}
          >
            {info.getValue()}
          </a>
        ) : (
          info.getValue()
        ),
      minSize: 350,
      size: 15,
    });
  }
  if (fields.includes("name")) {
    columns.push({
      accessorKey: "name",
      header: () => "Feature name",
      minSize: 150,
      size: 15,
    });
  }
  if (fields.includes("enabled")) {
    columns.push({
      accessorKey: "enabled",
      header: () => "Enabled",
      cell: (info: any) =>
        info.getValue() ? (
          <span className="activation-status">Enabled</span>
        ) : (
          <span className="activation-status disabled-status">Disabled</span>
        ),
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

  const customActions = {
    edit: {
      icon: (
        <>
          <i className="bi bi-pencil-square" aria-hidden></i> Edit
        </>
      ),
      hasRight: (user: TUser, overload: TContextOverload) => {
        return actions(overload).includes("edit");
      },
      customForm: (datum: TContextOverload, cancel: () => void) => {
        return (
          <>
            <h4>Edit overload</h4>
            <OverloadCreationForm
              project={datum.project!}
              defaultValue={datum}
              submit={(overload) =>
                updateStrategyMutation.mutateAsync(
                  {
                    feature: overload.name,
                    wasm: overload.wasmConfig,
                    ...overload,
                  } as any,
                  {
                    onSuccess: () => cancel(),
                  }
                )
              }
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
          <>
            <h4>Test feature</h4>
            <ExistingFeatureTestForm
              context={datum.path}
              feature={datum}
              cancel={cancel}
            />
          </>
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
        return actions(overload).includes("delete");
      },
      action: (overload: TContextOverload) =>
        askConfirmation(
          `Are you sure you want to delete overload ${overload.name} ?`,
          () =>
            deleteStrategyMutation.mutateAsync({
              feature: overload.name,
              path: overload.path!,
              project: overload.project as any,
            })
        ),
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
export function FeatureTestForm(props: {
  feature: TCompleteFeature;
  cancel?: () => any;
  noContext?: boolean;
}) {
  const { tenant } = useParams();
  const { control, register, handleSubmit } = useForm<FeatureTestType>();

  const [message, setMessage] = useState<string | undefined>(undefined);

  // TODO handle context
  const featureTestMutation = useMutation(
    ({
      feature,
      user,
      date,
    }: {
      date: Date;
      user: string;
      feature: TCompleteFeature;
    }) => testFeature(tenant!, feature, user, date)
  );

  return (
    <form
      onSubmit={handleSubmit(({ user, date, context }) => {
        setMessage("");
        featureTestMutation
          .mutateAsync({
            feature: props.feature,
            user: user ?? "",
            date,
          })
          .then(({ active }) => {
            setMessage(
              `feature ${props.feature.name} would be ${
                active ? "active" : "inactive"
              } on ${format(date, "yyyy-MM-dd")}${
                user ? ` for user ${user}` : ""
              }${context ? ` for context ${context}` : ""}`
            );
          });
      })}
      className="d-flex flex-column"
    >
      {!props.noContext && (
        <label>
          Context path
          <input
            type="text"
            className="form-control"
            {...register("context")}
          ></input>
        </label>
      )}
      <label className="col-6">
        Date
        <Controller
          name="date"
          defaultValue={new Date()}
          control={control}
          render={({ field: { onChange, value } }) => {
            return (
              <input
                className="form-control"
                defaultValue={format(new Date(), "yyyy-MM-dd")}
                value={value ? format(value, "yyyy-MM-dd") : ""}
                onChange={(e) => {
                  onChange(parse(e.target.value, "yyyy-MM-dd", new Date()));
                }}
                type="date"
              />
            );
          }}
        />
      </label>
      <label className="mt-2">
        User
        <input
          type="text"
          className="form-control"
          {...register("user")}
        ></input>
      </label>
      {message && (
        <div className="d-flex justify-content-end">
          <output className="m-2 py-1 px-3 anim__highlighter">{message}</output>
        </div>
      )}
      <div className="d-flex justify-content-end">
        {props.cancel && (
          <button
            type="button"
            className="btn btn-danger m-2"
            onClick={() => props.cancel?.()}
          >
            Cancel
          </button>
        )}
        <button type="submit" className="btn btn-secondary m-2">
          Test feature
        </button>
      </div>
    </form>
  );
}
export function FeatureDetails({ feature }: { feature: TLightFeature }) {
  if ("wasmConfig" in feature && feature.wasmConfig !== undefined) {
    return (
      <>
        {feature.description && <div>{feature.description}</div>}
        <ScriptDetails config={feature.wasmConfig} />
      </>
    );
  } else if (isSingleConditionFeature(feature)) {
    return (
      <>
        {feature.description && <div>{feature.description}</div>}
        <div className="fw-semibold">Active : </div>
        <SingleConditionFeatureDetail
          feature={toLegacyFeatureFormat(feature)}
        />
      </>
    );
  } else {
    return (
      <>
        {feature.description && <div>{feature.description}</div>}
        <ConditionDetails
          conditions={(feature as ClassicalFeature).conditions}
        />
      </>
    );
  }
}
export function FeatureTable(props: {
  features: TLightFeature[];
  fields: FeatureFields[];
  actions: (t: TLightFeature) => FeatureActionNames[];
  refresh: () => any;
}) {
  const { tenant } = useParams();
  const { fields, features, actions, refresh } = props;
  const [selectedRows, setSelectedRows] = useState<TLightFeature[]>([]);

  const columns: ColumnDef<TLightFeature>[] = [];

  const { askConfirmation, user } = React.useContext(IzanamiContext);

  const featureUpdateMutation = useMutation(
    (data: { id: string; feature: TCompleteFeature }) =>
      updateFeature(tenant!, data.id, data.feature),
    {
      onSuccess: () => {
        refresh();
      },
    }
  );

  const contextQueries = useQueries(
    [...new Set(features.map((f) => f.project))].map((project) => {
      return {
        queryKey: [projectContextKey(tenant!, project!)],
        queryFn: () => queryContextsForProject(tenant!, project!),
        enabled: fields.includes("overloadCount"),
      };
    })
  );

  const featureDeleteMutation = useMutation(
    (id: string) => deleteFeature(tenant!, id),
    {
      onSuccess: () => {
        refresh();
      },
    }
  );

  const featureCreateMutation = useMutation(
    (data: { project: string; feature: any }) =>
      createFeature(tenant!, data.project, data.feature),
    {
      onSuccess: () => refresh(),
    }
  );

  if (fields.includes("name") && fields.includes("overloadCount")) {
    columns.push({
      accessorKey: "name",
      header: () => "Feature name",
      minSize: 150,
      size: 15,
      cell: (props: { row: Row<any> }) => {
        const feature = props.row.original;

        if (contextQueries.some((q) => q.isError)) {
          return <div>Failed to fetch overload cound</div>;
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
                <span className="px-3">{feature.name}</span>
              </div>
            );
          } else {
            return (
              <div className="d-flex justify-content-between align-items-start mt-2">
                <span className="px-3">{feature.name}</span>
                <button
                  className="top-10 translate-middle badge rounded-pill bg-primary-outline"
                  role="button"
                  data-tooltip-id="paste_url"
                  data-tooltip-content="Overloads"
                  data-tooltip-place="top"
                  onClick={() =>
                    document
                      .getElementById(`overload-action-icon-${feature.id}`)
                      ?.click()
                  }
                >
                  <Tooltip id="paste_url" />
                  {maybeContexts.length}
                  <span className="visually-hidden">Overloads</span>
                </button>
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
      minSize: 150,
      size: 15,
    });
  }
  if (fields.includes("enabled")) {
    columns.push({
      accessorKey: "enabled",
      header: () => "Enabled",
      cell: (info: any) =>
        info.getValue() ? (
          <span className="activation-status">Enabled</span>
        ) : (
          <span className="activation-status disabled-status">Disabled</span>
        ),
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
                featureUpdateMutation.mutateAsync(
                  {
                    id: datum.id!,
                    feature,
                  },
                  {
                    onSuccess: () => {
                      queryClient.invalidateQueries(tagsQueryKey(tenant!));
                      cancel();
                    },
                  }
                );
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
        return (
          <>
            <h4>Test feature</h4>
            <ExistingFeatureTestForm feature={datum} cancel={cancel} />
          </>
        );
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
      action: (feature: TLightFeature) =>
        askConfirmation(
          `Are you sure you want to delete feature ${feature.name} ?`,
          () => featureDeleteMutation.mutateAsync(feature.id!)
        ),
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
        askConfirmation(<FeatureUrl tenant={tenant!} feature={feature} />),
    },
  };

  const [bulkOperation, setBulkOperation] = useState<string | undefined>(
    undefined
  );
  const selectableRows = features
    .map((f) => f.project!)
    .some((p) => hasRightForProject(user!, TLevel.Write, p, tenant!));
  return (
    <div>
      {selectableRows && (
        <div
          className={`d-flex align-items-center ${
            selectedRows.length > 0 ? "" : "invisible"
          }`}
        >
          <Select
            options={BULK_OPERATIONS.map((op) => ({ label: op, value: op }))}
            value={
              bulkOperation
                ? { label: bulkOperation, value: bulkOperation }
                : null
            }
            onChange={(e) => {
              setBulkOperation(e?.value);
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
              bulkOperation={bulkOperation!}
              selectedRows={features.filter((f) =>
                selectedRows.map((item) => item.id).includes(f.id)
              )}
              cancel={() => setBulkOperation(undefined)}
              refresh={() => refresh()}
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
      />
    </div>
  );
}
