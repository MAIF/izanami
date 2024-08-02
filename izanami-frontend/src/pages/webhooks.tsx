import { constraints, format, type } from "@maif/react-forms";
import { Form } from "../components/Form";
import * as React from "react";
import { WEBHOOK_NAME_REGEXP } from "../utils/patterns";
import { useMutation, useQuery } from "react-query";
import {
  createWebhook,
  deleteWebhook,
  fetchWebhookUsers,
  fetchWebhooks,
  queryTenant,
  tenantQueryKey,
  updateWebhook,
  updateWebhookRightsFor,
  webhookQueryKey,
  webhookUserQueryKey,
} from "../utils/queries";
import { Loader } from "../components/Loader";
import { Tooltip } from "../components/Tooltip";
import { AllContexts } from "../components/AllContextSelect";
import { FeatureSelector } from "../components/FeatureSelector";
import { ProjectSelector } from "../components/ProjectSelector";
import { LightWebhook, TLevel, Webhook } from "../utils/types";
import { GenericTable } from "../components/GenericTable";
import { Link, useSearchParams } from "react-router-dom";
import {
  IzanamiContext,
  hasRightForWebhook,
  useTenantRight,
} from "../securityContext";
import queryClient from "../queryClient";

import { RightTable } from "../components/RightTable";
import { useState } from "react";
import { InvitationForm } from "../components/InvitationForm";
import { WebhookTransformationEditor } from "../components/Editor";
import Handlebars from "handlebars";
import { Row } from "@tanstack/react-table";

export function WebHooks(props: { tenant: string }) {
  const [searchParams] = useSearchParams();
  const selectedSearchRow = searchParams.get("filter");
  const tenant = props.tenant;
  const [creating, setCreating] = React.useState(false);
  const { askConfirmation, refreshUser } = React.useContext(IzanamiContext);
  const hasTenantWriteLevel = useTenantRight(tenant, TLevel.Write);

  const webhookCreationMutation = useMutation(
    (data: { webhook: LightWebhook }) => createWebhook(tenant!, data.webhook),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(webhookQueryKey(tenant));
        refreshUser();
      },
    }
  );

  const webhookUpdateMutation = useMutation(
    (data: { id: string; webhook: LightWebhook }) =>
      updateWebhook(tenant!, data.id, data.webhook),
    {
      onSuccess: () => queryClient.invalidateQueries(webhookQueryKey(tenant)),
    }
  );

  const webhookDeletion = useMutation(
    (data: { id: string }) => deleteWebhook(tenant!, data.id),
    {
      onSuccess: () => queryClient.invalidateQueries(webhookQueryKey(tenant)),
    }
  );

  const webhookQuery = useQuery(webhookQueryKey(tenant), () =>
    fetchWebhooks(tenant)
  );

  if (webhookQuery.isError) {
    return <div>Failed to fetch webhooks for this tenant</div>;
  } else if (webhookQuery.data) {
    return (
      <>
        <div className="d-flex align-items-center">
          <h1>Webhooks</h1>
          {hasTenantWriteLevel && !creating && webhookQuery?.data?.length > 0 && (
            <button
              className="btn btn-primary btn-sm mb-2 ms-3"
              type="button"
              onClick={() => setCreating(true)}
            >
              Create new webhook
            </button>
          )}
        </div>
        {creating && (
          <WebHookCreationForm
            tenant={tenant}
            cancel={() => setCreating(false)}
            submit={(webhook) => {
              const bodyOverloadedActive = webhook.bodyOverloadedActive;
              const objectHeaders = webhook.headers.reduce(
                (
                  acc: { [x: string]: string },
                  { name, value }: { name: string; value: string }
                ) => {
                  acc[name] = value;
                  return acc;
                },
                {}
              );
              return webhookCreationMutation
                .mutateAsync({
                  webhook: {
                    ...webhook,
                    headers: objectHeaders,
                    bodyTemplate: bodyOverloadedActive
                      ? webhook.bodyTemplate
                      : undefined,
                  },
                })
                .then(() => setCreating(false));
            }}
          />
        )}
        {!creating && webhookQuery.data.length === 0 && (
          <div className="item-block">
            <div className="item-text">
              There is no webhooks for this tenant.
            </div>
            <button
              type="button"
              className="btn btn-primary btn-lg"
              onClick={() => setCreating(true)}
            >
              Create new webhook
            </button>
          </div>
        )}
        {webhookQuery.data.length > 0 && (
          <GenericTable
            idAccessor={(hook) => hook.id}
            data={webhookQuery.data}
            filters={
              selectedSearchRow
                ? [{ id: "name", value: selectedSearchRow }]
                : []
            }
            columns={[
              {
                accessorKey: "name",
                header: () => "Name",
                size: 15,
                minSize: 100,
              },
              {
                accessorKey: "enabled",
                cell: (info: any) =>
                  info.getValue() ? (
                    <span className="activation-status enabled-status">
                      Enabled
                    </span>
                  ) : (
                    <span className="activation-status">Disabled</span>
                  ),
                header: () => "Status",
                minSize: 150,
                size: 10,
                meta: {
                  valueType: "status",
                },
              },
              {
                id: "description",
                header: () => "Description",
                size: 20,
                cell: (props: any) => {
                  return <>{props.getValue()}</>;
                },
              },
              {
                accessorKey: "url",
                header: () => "URL",
                size: 25,
              },
              {
                header: () => "Features / projects",
                id: "scope",
                minSize: 200,
                size: 25,
                filterFn: (
                  row: Row<Webhook>,
                  columnId: string,
                  filterValue: any
                ) => {
                  if (!filterValue || filterValue?.length === 0) {
                    return true;
                  }
                  const featureMatch = Boolean(
                    row.original?.features?.find(({ name }) =>
                      name.includes(filterValue)
                    ) || false
                  );

                  const projectMatch = Boolean(
                    row.original?.projects?.find(({ name }) =>
                      name.includes(filterValue)
                    ) || false
                  );

                  return featureMatch || projectMatch;
                },
                cell: (info) => {
                  const features = info.row.original?.features ?? [];
                  const projects = info.row.original?.projects ?? [];
                  const global = info.row.original?.global;

                  return global ? (
                    <>
                      <i className="fas fa-globe" aria-hidden />
                      &nbsp;global
                    </>
                  ) : (
                    <>
                      {projects.map(({ name, id }) => (
                        <div key={id} className="mt-1">
                          <Link to={`/tenants/${tenant}/projects/${name}`}>
                            <button className="btn btn-sm btn-primary mr-0">
                              <i className="fas fa-building" aria-hidden />{" "}
                              {name}
                            </button>
                          </Link>
                        </div>
                      ))}
                      {features.map(({ project, name, id }) => (
                        <div key={id} className="mt-1">
                          <Link to={`/tenants/${tenant}/projects/${project}`}>
                            <button
                              className="btn btn-sm btn-primary"
                              style={{ marginRight: 0 }}
                            >
                              {name}&nbsp; (
                              <i className="fas fa-building" aria-hidden />{" "}
                              {project})
                            </button>
                          </Link>
                        </div>
                      ))}
                    </>
                  );
                },
              },
            ]}
            customRowActions={{
              edit: {
                icon: (
                  <>
                    <i className="bi bi-pencil-square" aria-hidden></i> Edit
                  </>
                ),
                hasRight: (user, webhook) =>
                  hasRightForWebhook(user, TLevel.Write, webhook.name, tenant),
                customForm: (data, cancel) => (
                  <WebHookCreationForm
                    tenant={tenant}
                    cancel={() => cancel()}
                    submit={(hook) => {
                      return webhookUpdateMutation
                        .mutateAsync({
                          id: data.id,
                          webhook: {
                            ...hook,
                            bodyTemplate: hook.bodyOverloadedActive
                              ? hook.bodyTemplate
                              : undefined,
                          },
                        })
                        .then(() => cancel());
                    }}
                    defaultValue={data}
                  />
                ),
              },
              rights: {
                icon: (
                  <>
                    <i className="fa-solid fa-lock" aria-hidden></i> Rights
                  </>
                ),
                hasRight: (user, webhook) =>
                  hasRightForWebhook(user, TLevel.Admin, webhook.name, tenant),
                customForm: (webhook: Webhook, cancel) => (
                  <>
                    <WebhookRightTable tenant={tenant} webhook={webhook} />
                    <div className="d-flex justify-content-end">
                      <button className="btn btn-danger m-2" onClick={cancel}>
                        Cancel
                      </button>
                    </div>
                  </>
                ),
              },
              delete: {
                icon: (
                  <>
                    <i className="bi bi-trash" aria-hidden></i>&nbsp;Delete
                  </>
                ),
                action: (webhook: Webhook) =>
                  askConfirmation(
                    `Are you sure you want to delete webhook ${webhook.name} ?`,
                    () => webhookDeletion.mutateAsync({ id: webhook.id })
                  ),
                hasRight: (user, webhook) =>
                  hasRightForWebhook(user, TLevel.Admin, webhook.name, tenant),
              },
            }}
          />
        )}
      </>
    );
  } else {
    return <Loader message="Loading webhooks..." />;
  }
}

function WebhookRightTable(props: { tenant: string; webhook: Webhook }) {
  const { tenant, webhook } = props;
  const [creating, setCreating] = useState(false);

  const webhookRighQuery = useQuery(
    webhookUserQueryKey(tenant, webhook.id),
    () => fetchWebhookUsers(tenant, webhook.id)
  );

  const webhookRightUpdateMutation = useMutation(
    (data: { user: string; right?: TLevel }) =>
      updateWebhookRightsFor(tenant, webhook.id, data.user, data.right),
    {
      onSuccess: () =>
        queryClient.invalidateQueries(webhookUserQueryKey(tenant, webhook.id)),
    }
  );

  if (webhookRighQuery.error) {
    return <div>Failed to retrieve webhook users</div>;
  } else if (webhookRighQuery.data) {
    return (
      <>
        <h4>
          Authorized users for {webhook.name}
          <button
            className="btn btn-secondary btn-sm ms-3"
            type="button"
            onClick={() => setCreating(true)}
          >
            Invite user
          </button>
        </h4>
        {creating && (
          <InvitationForm
            cancel={() => setCreating(false)}
            submit={({ users, level }) => {
              Promise.all(
                users.map((user) => {
                  webhookRightUpdateMutation.mutateAsync({
                    user,
                    right: level,
                  });
                })
              ).then(() => setCreating(false));
            }}
          />
        )}
        <RightTable
          data={webhookRighQuery.data}
          canEdit={true}
          onRightChange={(datum, level) =>
            webhookRightUpdateMutation.mutateAsync({
              user: datum.username,
              right: level,
            })
          }
        />
      </>
    );
  } else {
    return <Loader message="Loading webhook users..." />;
  }
}

function WebHookCreationForm(props: {
  tenant: string;
  cancel: () => void;
  submit: (data: any) => Promise<void>;
  defaultValue?: Webhook;
}) {
  const tenant = props.tenant;
  const projectQuery = useQuery(tenantQueryKey(tenant), () =>
    queryTenant(tenant)
  );
  const { user } = React.useContext(IzanamiContext);
  const maybeDefault = props.defaultValue;

  if (projectQuery.isError) {
    return <div>Failed to fetch projects for this tenant</div>;
  } else if (projectQuery.data) {
    return (
      <div className="sub_container anim__rightToLeft">
        <h4>Create new webhook</h4>
        <Form
          schema={{
            name: {
              deps: [],
              defaultValue: maybeDefault?.name ?? "",
              label: "Name",
              required: true,
              tooltip: () => (
                <>
                  Name of the webhook.
                  <br />
                  Use something meaningful, it can be modified without impact.
                </>
              ),
              type: type.string,
              props: {
                autoFocus: true,
              },
              constraints: [
                constraints.matches(
                  WEBHOOK_NAME_REGEXP,
                  `Key name must match regex ${WEBHOOK_NAME_REGEXP.toString()}`
                ),
              ],
            },
            enabled: {
              deps: [],
              defaultValue: maybeDefault?.enabled ?? false,
              label: "Enabled",
              type: type.bool,
            },
            description: {
              deps: [],
              label: "Description",
              type: type.string,
              format: format.textarea,
              defaultValue: maybeDefault?.description ?? "",
            },
            url: {
              deps: [],
              label: "URL",
              required: true,
              tooltip: () => (
                <>
                  URL to call.
                  <br />
                  This will be called each time related features are modified.
                </>
              ),
              type: type.string,
              defaultValue: maybeDefault?.url ?? "",
              constraints: [
                constraints.test(
                  "url",
                  "Should be a valid http/https url",
                  (value) => {
                    try {
                      new URL(value);
                      return value.startsWith("http");
                    } catch (err) {
                      return false;
                    }
                  }
                ),
              ],
            },
            headers: {
              deps: [],
              label: () => (
                <>
                  Headers
                  <Tooltip id="webhook-headers">
                    Headers to use for HTTP(S) calls.
                    <br />
                    This can be used to provide some authentication information.
                  </Tooltip>
                </>
              ),
              type: type.object,
              defaultValue: Object.entries(maybeDefault?.headers ?? {}).map(
                ([key, value]) => ({ name: key, value })
              ),
              array: true,
              render: ({ value, onChange }) => {
                return (
                  <Headers
                    value={value}
                    onChange={(newArray) => {
                      onChange?.(newArray);
                    }}
                  />
                );
              },
            },
            ...(user?.admin
              ? {
                  global: {
                    deps: [],
                    label: () => (
                      <>
                        Global
                        <Tooltip id="webhooks-global">
                          Global webhooks are called for every feature update
                          for this tenant.
                        </Tooltip>
                      </>
                    ),
                    defaultValue: maybeDefault?.global ?? false,
                    type: type.bool,
                  },
                }
              : {}),
            features: {
              deps: ["projects", "global"],
              visible: ({ rawValues }) => {
                return !rawValues.global;
              },
              defaultValue: maybeDefault?.features?.map((f) => f.id) ?? [],
              label: () => (
                <>
                  <label htmlFor="webhook-features-select">
                    Features (project)
                  </label>
                  <Tooltip id="webhooks-features">
                    Update of selected features will trigger calls on provided
                    URL.
                  </Tooltip>
                </>
              ),
              isMulti: true,
              type: type.string,
              format: format.select,
              render: ({ value, onChange }) => {
                return (
                  <FeatureSelector
                    id="webhook-features-select"
                    value={value}
                    onChange={onChange}
                  />
                );
              },
              arrayConstraints: [
                constraints.test(
                  "feature-or-project",
                  "You must select at least one feature or project",
                  (value, { parent: { projects, global } }) => {
                    return global || value.length > 0 || projects.length > 0;
                  }
                ),
              ],
            },
            projects: {
              deps: ["features", "global"],
              visible: ({ rawValues }) => {
                return !rawValues.global;
              },
              defaultValue: maybeDefault?.projects?.map((p) => p.id) ?? [],
              label: () => (
                <>
                  <label htmlFor="webhook-projects-select">Projects</label>
                  <Tooltip id="webhook-projects">
                    Update of one of these projects features
                    <br />
                    will trigger calls on provided URL.
                  </Tooltip>
                </>
              ),
              type: type.string,
              isMulti: true,
              format: format.select,
              render: ({ value, onChange }) => {
                return (
                  <ProjectSelector
                    id="webhook-projects-select"
                    value={value}
                    onChange={onChange}
                  />
                );
              },
              arrayConstraints: [
                constraints.test(
                  "feature-or-project",
                  "You must select at least one feature or project",
                  (value, { parent: { features, global } }) => {
                    return global || value.length > 0 || features.length > 0;
                  }
                ),
              ],
            },
            context: {
              deps: [],
              defaultValue: maybeDefault?.context ?? "",
              label: () => (
                <>
                  <label htmlFor="webhook-context-select">Context</label>
                  <Tooltip id="webhook-context">
                    Context to use for activation and conditions.
                  </Tooltip>
                </>
              ),
              type: type.string,
              render: ({ value, onChange }) => {
                return (
                  <AllContexts
                    id="webhook-context-select"
                    onChange={(v) => onChange?.(v)}
                    value={value}
                  />
                );
              },
            },
            user: {
              deps: [],
              defaultValue: maybeDefault?.user ?? "",
              label: () => {
                return (
                  <>
                    User
                    <Tooltip id="webhook-context">
                      User used to compute user-based activation conditions
                      <br />
                      such as percentages and user list features.
                    </Tooltip>
                  </>
                );
              },
              type: type.string,
            },
            bodyOverloadedActive: {
              deps: [],
              label: () => (
                <>
                  Custom body
                  <Tooltip id="webhook-body-format-customization-tooltip">
                    Allow replacing the built-in webhook call body with a custom
                    body.
                  </Tooltip>
                </>
              ),
              type: type.bool,
              defaultValue: !!maybeDefault?.bodyTemplate,
            },
            bodyTemplate: {
              deps: ["bodyOverloadedActive"],
              constraints: [
                constraints.test(
                  "handlebars",
                  "Should be a valid handlebar template",
                  (value) => {
                    try {
                      const template = Handlebars.compile(value);
                      template({});
                      return true;
                    } catch (e) {
                      console.error(e);
                      return false;
                    }
                  }
                ),
              ],
              visible: ({ rawValues: { bodyOverloadedActive } }) =>
                bodyOverloadedActive,
              label: () => {
                return "";
              },
              type: type.string,
              defaultValue:
                maybeDefault?.bodyTemplate ??
                `{
  "active": {{payload.active}}
}`,
              render: ({ value, onChange }) => {
                return (
                  <WebhookTransformationEditor
                    value={value}
                    onChange={(v) => onChange?.(v)}
                  />
                );
              },
            },
          }}
          onSubmit={(webhook: any) => {
            if (webhook.global) {
              return props.submit({ ...webhook, features: [], projects: [] });
            } else {
              return props.submit(webhook);
            }
          }}
          footer={({ valid }: { valid: () => void }) => (
            <div className="d-flex justify-content-end mt-3">
              <button
                type="button"
                className="btn btn-danger-light m-2"
                onClick={() => props.cancel()}
              >
                Cancel
              </button>
              <button className="btn btn-primary m-2" onClick={valid}>
                Save
              </button>
            </div>
          )}
        />
      </div>
    );
  } else {
    return <Loader message="Loading projects..." />;
  }
}

function Headers(props: {
  value: { name: string; value: string }[];
  onChange: (newValue: { name: string; value: string }[]) => void;
}) {
  const arr = props.value;
  return (
    <div className="container-fluid row-gap-3">
      {arr.length > 0 && (
        <div className="row my-1">
          <label className="col-6 col-lg-3">Header name</label>
          <label className="col">Header value</label>
        </div>
      )}
      {arr.map(({ name, value }, index) => (
        <div className="row my-1" key={`header-${index}`}>
          <div className="col-6 col-lg-3">
            <input
              aria-label={`header-${index}-name`}
              className="form-control"
              value={name}
              onChange={(e) => {
                const v = e.target.value;
                props.onChange([
                  ...arr
                    .slice(0, index)
                    .concat([{ name: v, value }])
                    .concat(arr.slice(index + 1)),
                ]);
              }}
            />
          </div>
          <div className="col">
            <input
              className="form-control"
              aria-label={`header-${index}-value`}
              value={value}
              onChange={(e) => {
                const v = e.target.value;
                props.onChange([
                  ...arr
                    .slice(0, index)
                    .concat([{ name, value: v }])
                    .concat(arr.slice(index + 1)),
                ]);
              }}
            />
          </div>
          <div className="col-1 d-flex justify-content-end">
            <button
              className="btn btn-danger"
              type="button"
              onClick={() => {
                props.onChange([
                  ...arr.slice(0, index).concat(arr.slice(index + 1)),
                ]);
              }}
            >
              Delete
            </button>
          </div>
        </div>
      ))}
      <div className="row justify-content-end mt-3">
        <div className="col-auto">
          <button
            className="btn btn-secondary"
            type="button"
            onClick={() => {
              props.onChange([...arr, { name: "", value: "" }]);
            }}
          >
            Add header
          </button>
        </div>
      </div>
    </div>
  );
}
