import * as React from "react";
import { useContext, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Select from "react-select";
import {
  findKeyRight,
  findProjectRight,
  findTenantRight,
  IzanamiContext,
  isRightBelowOrEqual,
} from "../securityContext";
import { customStyles } from "../styles/reactSelect";
import {
  MutationNames,
  fetchWebhooks,
  queryKeys,
  queryTenant,
  queryTenants,
  tenantKeyQueryKey,
  tenantQueryKey,
  webhookQueryKey,
} from "../utils/queries";
import {
  TLevel,
  TLevelArray,
  TLevelWithNone,
  TLevelWithNoneArray,
  TProjectLevel,
  TProjectLevelArray,
  TProjectLevelWithNone,
  TRights,
} from "../utils/types";
import { Loader } from "./Loader";
import { State } from "../utils/rightUtils";
import { Tooltip } from "./Tooltip";

const EventType = {
  SelectTenant: "SelectTenant",
  DeleteTenant: "DeleteTenant",
  SelectProject: "SelectProject",
  DeleteProject: "DeleteProject",
  SetTenantLevel: "SetTenantLevel",
  SetProjectLevel: "SetProjectLevel",
  SelectKey: "SelectKey",
  DeleteKey: "DeleteKey",
  SetKeyLevel: "SetKeyLevel",
  SetupState: "SetupState",
  SetWebhookLevel: "SetWebhookLevel",
  SelectWebhook: "SelectWebhook",
  DeleteWebhook: "DeleteWebhook",
  SetDefaultProjectLevel: "SetDefaultProjectLevel",
  SetDefaultKeyLevel: "SetDefaultKeyLevel",
  SetDefaultWebhookLevel: "SetDefaultWebhookLevel",
  SetMaxProjectLevel: "SetMaxProjectLevel",
  SetMaxKeyLevel: "SetMaxKeyLevel",
  SetMaxWebhookLevel: "SetMaxWebhookLevel",
  SetMaxTenantLevel: "SetMaxTenantLevel",
} as const;

type EventType = typeof EventType[keyof typeof EventType];

interface Event {
  type: EventType;
}

interface ProjectSelectionEvent extends Event {
  type: "SelectProject";
  name: string;
  tenant: string;
}

interface TenantSelectionEvent extends Event {
  type: "SelectTenant";
  name: string;
}

interface ProjectDeleteEvent extends Event {
  type: "DeleteProject";
  name: string;
  tenant: string;
}

interface TenantDeleteEvent extends Event {
  type: "DeleteTenant";
  name: string;
}

interface TenantLevelEvent extends Event {
  type: "SetTenantLevel";
  level: TLevelWithNone;
  name: string;
}

interface ProjectLevelEvent extends Event {
  type: "SetProjectLevel";
  level: TProjectLevel;
  tenant: string;
  name: string;
}

interface KeySelectionEvent extends Event {
  type: "SelectKey";
  name: string;
  tenant: string;
}

interface KeyDeleteEvent extends Event {
  type: "DeleteKey";
  name: string;
  tenant: string;
}

interface KeyLevelEvent extends Event {
  type: "SetKeyLevel";
  level: TLevel;
  tenant: string;
  name: string;
}

interface WebhookSelectionEvent extends Event {
  type: "SelectWebhook";
  name: string;
  tenant: string;
}

interface WebhookLevelEvent extends Event {
  type: "SetWebhookLevel";
  level: TLevel;
  tenant: string;
  name: string;
}

interface WebhookDeleteEvent extends Event {
  type: "DeleteWebhook";
  tenant: string;
  name: string;
}

interface InitialSetup extends Event {
  type: "SetupState";
  rights: TRights;
}

interface SetDefaultProjectLevelEvent extends Event {
  type: "SetDefaultProjectLevel";
  tenant: string;
  level: TProjectLevelWithNone;
}

interface SetMaxProjectLevelEvent extends Event {
  type: "SetMaxProjectLevel";
  tenant: string;
  level: TProjectLevelWithNone;
}

interface SetDefaultKeyLevelEvent extends Event {
  type: "SetDefaultKeyLevel";
  tenant: string;
  level: TLevelWithNone;
}

interface SetMaxKeyLevelEvent extends Event {
  type: "SetMaxKeyLevel";
  tenant: string;
  level: TLevelWithNone;
}

interface SetDefaultWebhookLevelEvent extends Event {
  type: "SetDefaultWebhookLevel";
  tenant: string;
  level: TLevelWithNone;
}

interface SetMaxWebhookLevelEvent extends Event {
  type: "SetMaxWebhookLevel";
  tenant: string;
  level: TLevelWithNone;
}

interface SetMaxTenantLevelEvent extends Event {
  type: "SetMaxTenantLevel";
  tenant: string;
  level: TLevelWithNone;
}

type EventTypes =
  | ProjectSelectionEvent
  | TenantSelectionEvent
  | ProjectDeleteEvent
  | TenantDeleteEvent
  | TenantLevelEvent
  | ProjectLevelEvent
  | KeySelectionEvent
  | KeyDeleteEvent
  | KeyLevelEvent
  | InitialSetup
  | WebhookSelectionEvent
  | WebhookLevelEvent
  | WebhookDeleteEvent
  | SetDefaultProjectLevelEvent
  | SetDefaultKeyLevelEvent
  | SetDefaultWebhookLevelEvent
  | SetMaxProjectLevelEvent
  | SetMaxKeyLevelEvent
  | SetMaxWebhookLevelEvent
  | SetMaxTenantLevelEvent;

function rightToInternalState(rights: TRights): State {
  if (!rights?.tenants) {
    return [];
  } else {
    return Object.entries(rights.tenants).map(([key, value]) => ({
      name: key,
      level: value.level,
      projects: Object.entries(value.projects ?? {}).map(
        ([projectName, projectValue]) => ({
          name: projectName,
          level: projectValue.level,
        })
      ),
      keys: Object.entries(value.keys ?? {}).map(([keyName, keyValue]) => ({
        name: keyName,
        level: keyValue.level,
      })),
      webhooks: Object.entries(value.webhooks ?? {}).map(
        ([keyName, keyValue]) => ({
          name: keyName,
          level: keyValue.level,
        })
      ),
      defaultProjectRight: value.defaultProjectRight,
      defaultKeyRight: value.defaultKeyRight,
      defaultWebhookRight: value.defaultWebhookRight,
      maxProjectRight: value.maxProjectRight,
      maxKeyRight: value.maxKeyRight,
      maxWebhookRight: value.maxWebhookRight,
      maxTenantRight: value.maxTenantRight,
    }));
  }
}

const reducer = function reducer(state: State, event: EventTypes): State {
  switch (event.type) {
    case EventType.SetupState: {
      return rightToInternalState(event.rights);
    }
    case EventType.DeleteProject:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          const projects = el.projects;

          return {
            ...el,
            projects: [...projects].filter(({ name }) => name !== event.name),
          };
        }
        return el;
      });
    case EventType.DeleteTenant:
      return [...state].filter(({ name }) => name !== event.name);
    case EventType.SelectProject:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          const projects = el.projects;

          return {
            ...el,
            projects: [...projects, { name: event.name, level: TLevel.Read }],
          };
        }
        return el;
      });
    case EventType.SelectTenant:
      return [
        ...state,
        {
          name: event.name,
          level: TLevel.Read,
          projects: [],
          keys: [],
          webhooks: [],
          defaultProjectRight: TProjectLevelWithNone.None,
          defaultKeyRight: TLevelWithNone.None,
          defaultWebhookRight: TLevelWithNone.None,
          maxKeyRight: TLevelWithNone.Admin,
          maxProjectRight: TLevelWithNone.Admin,
          maxTenantRight: TLevelWithNone.Admin,
          maxWebhookRight: TLevelWithNone.Admin,
        },
      ];
    case EventType.SetTenantLevel:
      return [...state].map((el) => {
        if (el.name === event.name) {
          return { ...el, level: event.level };
        }
        return el;
      });
    case EventType.SetProjectLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            projects: el.projects.map((p) => {
              if (p.name === event.name) {
                return { ...p, level: event.level };
              }
              return p;
            }),
          } as any;
        }
        return el;
      });
    case EventType.SetKeyLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            keys: el.keys.map((k) => {
              if (k.name === event.name) {
                return { ...k, level: event.level };
              }
              return k;
            }),
          };
        }
        return el;
      });
    case EventType.DeleteKey:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          const keys = el.keys;

          return {
            ...el,
            keys: [...keys].filter(({ name }) => name !== event.name),
          };
        }
        return el;
      });
    case EventType.SelectKey:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          const keys = el.keys;

          return {
            ...el,
            keys: [...keys, { name: event.name, level: TLevel.Read }],
          };
        }
        return el;
      });
    case EventType.SetWebhookLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            webhooks: el.webhooks.map((w) => {
              if (w.name === event.name) {
                return { ...w, level: event.level };
              }
              return w;
            }),
          };
        }
        return el;
      });
    case EventType.DeleteWebhook:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          const webhooks = el.webhooks;

          return {
            ...el,
            webhooks: [...webhooks].filter(({ name }) => name !== event.name),
          };
        }
        return el;
      });
    case EventType.SelectWebhook:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          const webhooks = el.webhooks;

          return {
            ...el,
            webhooks: [...webhooks, { name: event.name, level: TLevel.Read }],
          };
        }
        return el;
      });
    case EventType.SetDefaultProjectLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            defaultProjectRight: event.level,
          };
        }
        return el;
      });
    case EventType.SetMaxProjectLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            maxProjectRight: event.level,
          };
        }
        return el;
      });
    case EventType.SetDefaultKeyLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            defaultKeyRight: event.level,
          };
        }
        return el;
      });
    case EventType.SetMaxKeyLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            maxKeyRight: event.level,
          };
        }
        return el;
      });
    case EventType.SetDefaultWebhookLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            defaultWebhookRight: event.level,
          };
        }
        return el;
      });
    case EventType.SetMaxWebhookLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            maxWebhookRight: event.level,
          };
        }
        return el;
      });
    case EventType.SetMaxTenantLevel:
      return [...state].map((el) => {
        if (el.name === event.tenant) {
          return {
            ...el,
            maxTenantRight: event.level,
          };
        }
        return el;
      });
  }
};

function isValid(state: State): boolean {
  // FIXME return error to display id
  return state.every(({ name, projects, keys, webhooks }) => {
    return (
      name &&
      projects.every(({ name, level }) => name && level) &&
      keys.every(({ name, level }) => name && level) &&
      webhooks.every(({ name, level }) => name && level)
    );
  });
}

export function RightSelector(props: {
  defaultValue?: TRights;
  tenantLevelFilter?: TLevel;
  tenant?: string;
  onChange: (value: State) => void;
  maxRights: boolean;
}) {
  const { defaultValue, tenantLevelFilter, onChange, maxRights } = props;
  const tenantQuery = useQuery({
    queryKey: [MutationNames.TENANTS],

    queryFn: () => queryTenants(tenantLevelFilter),
  });

  const [state, dispatch] = React.useReducer(
    reducer,
    rightToInternalState(defaultValue ?? {})
  );
  React.useEffect(() => {
    if (defaultValue) {
      dispatch({ type: EventType.SetupState, rights: defaultValue });
    }
  }, [defaultValue]);
  React.useEffect(() => {
    if (isValid(state)) {
      onChange(state);
    }
  }, [state]);

  const selectedTenants = state.map(({ name }) => name);
  const [creating, setCreating] = useState(false);

  const { user } = useContext(IzanamiContext);
  const { admin, rights } = user!;

  if (tenantQuery.isLoading) {
    return <Loader message="Loading tenants.." />;
  } else if (tenantQuery.data) {
    const tenants = props.tenant
      ? [props.tenant]
      : tenantQuery.data.map((t) => t.name);
    const selectorChoices = props.tenant
      ? [props.tenant]
      : tenants.filter((item) => {
          return !selectedTenants.includes(item);
        });
    return (
      <div>
        <>
          {state.map(
            ({
              name,
              level,
              projects,
              keys,
              webhooks,
              defaultProjectRight,
              defaultKeyRight,
              defaultWebhookRight,
              maxProjectRight,
              maxKeyRight,
              maxWebhookRight,
              maxTenantRight,
            }) => {
              return (
                <>
                  <label className="mt-3">
                    <i className="fas fa-cloud" aria-hidden></i>&nbsp;Tenant
                  </label>
                  <div className="tenant-selector" key={name}>
                    <ItemSelector
                      label="Tenant"
                      rightOnly={!!props.tenant}
                      level={level}
                      key={name}
                      name={name}
                      choices={selectorChoices}
                      userRight={
                        admin
                          ? TLevel.Admin
                          : findTenantRight(rights, name) || TLevel.Read
                      }
                      onItemChange={(item) => {
                        dispatch({ type: EventType.DeleteTenant, name });
                        dispatch({ type: EventType.SelectTenant, name: item });
                      }}
                      onLevelChange={(level) => {
                        dispatch({
                          type: EventType.SetTenantLevel,
                          level,
                          name,
                        });
                      }}
                      onClear={() => {
                        dispatch({ type: EventType.DeleteTenant, name });
                      }}
                      possibleRights={TLevelWithNoneArray}
                    />
                    {level != "None" && (
                      <>
                        <div className="sub_container sub_container-bglighter project-selector">
                          <label>
                            <i className="fas fa-building" aria-hidden></i>
                            &nbsp;Project rights for {name}
                          </label>
                          <div>
                            <div className="d-flex flex-row justify-content-between">
                              <label
                                className="d-flex flex-row align-items-center"
                                style={{ marginTop: "1.5rem" }}
                              >
                                <span
                                  style={{
                                    marginRight: "1rem",
                                  }}
                                >
                                  Default project right
                                </span>
                                <Select
                                  value={
                                    defaultProjectRight
                                      ? {
                                          label: defaultProjectRight,
                                          value: defaultProjectRight,
                                        }
                                      : { label: "None", value: "None" }
                                  }
                                  options={[
                                    { label: "None", value: "None" },
                                  ].concat(
                                    Object.entries(TProjectLevel).map(
                                      ([key, value]) => ({
                                        label: value,
                                        value: key,
                                      })
                                    )
                                  )}
                                  styles={customStyles}
                                  onChange={(selected) => {
                                    dispatch({
                                      type: EventType.SetDefaultProjectLevel,
                                      tenant: name,
                                      level: (selected?.value === "None"
                                        ? undefined
                                        : selected?.value) as TProjectLevel,
                                    });
                                  }}
                                />
                              </label>
                            </div>
                            <div className="my-2">
                              <ProjectSelector
                                tenant={name}
                                dispatch={dispatch}
                                projects={projects}
                              />
                            </div>
                          </div>
                        </div>
                        <div className="sub_container sub_container-bglighter key-selector">
                          <label>
                            <i className="fas fa-key" aria-hidden></i>&nbsp;Keys
                            rights for {name}
                          </label>
                          <div>
                            <div className="d-flex flex-row justify-content-between">
                              <label
                                className="d-flex flex-row align-items-center"
                                style={{ marginTop: "1.5rem" }}
                              >
                                <span
                                  style={{
                                    marginRight: "1rem",
                                  }}
                                >
                                  Default key right
                                </span>
                                <Select
                                  value={
                                    defaultKeyRight
                                      ? {
                                          label: defaultKeyRight,
                                          value: defaultKeyRight,
                                        }
                                      : { label: "None", value: "None" }
                                  }
                                  options={[
                                    { label: "None", value: "None" },
                                  ].concat(
                                    Object.entries(TLevel).map(
                                      ([key, value]) => ({
                                        label: value,
                                        value: key,
                                      })
                                    )
                                  )}
                                  styles={customStyles}
                                  onChange={(selected) => {
                                    dispatch({
                                      type: EventType.SetDefaultKeyLevel,
                                      tenant: name,
                                      level: (selected?.value === "None"
                                        ? undefined
                                        : selected?.value) as TLevel,
                                    });
                                  }}
                                />
                              </label>
                            </div>
                            <div className="my-2">
                              <KeySelector
                                tenant={name}
                                dispatch={dispatch}
                                keys={keys}
                              />
                            </div>
                          </div>
                        </div>
                        <div className="sub_container sub_container-bglighter key-selector">
                          <label>
                            <i className="fas fa-plug" aria-hidden></i>
                            &nbsp;Webhooks rights for {name}
                          </label>
                          <div>
                            <div className="d-flex flex-row justify-content-between">
                              <label
                                className="d-flex flex-row align-items-center"
                                style={{ marginTop: "1.5rem" }}
                              >
                                <span
                                  style={{
                                    marginRight: "1rem",
                                  }}
                                >
                                  Default webhook right
                                </span>
                                <Select
                                  value={
                                    defaultWebhookRight
                                      ? {
                                          label: defaultWebhookRight,
                                          value: defaultWebhookRight,
                                        }
                                      : { label: "None", value: "None" }
                                  }
                                  options={[
                                    { label: "None", value: "None" },
                                  ].concat(
                                    Object.entries(TLevel).map(
                                      ([key, value]) => ({
                                        label: value,
                                        value: key,
                                      })
                                    )
                                  )}
                                  styles={customStyles}
                                  onChange={(selected) => {
                                    dispatch({
                                      type: EventType.SetDefaultWebhookLevel,
                                      tenant: name,
                                      level: (selected?.value === "None"
                                        ? undefined
                                        : selected?.value) as TLevel,
                                    });
                                  }}
                                />
                              </label>
                            </div>
                            <div className="my-2">
                              <WebhookSelector
                                tenant={name}
                                dispatch={dispatch}
                                webhooks={webhooks}
                              />
                            </div>
                          </div>
                        </div>
                      </>
                    )}
                    {maxRights && (
                      <div className="sub_container sub_container-bglighter project-selector">
                        <div>
                          <i className="fa-solid fa-lock" aria-hidden></i>
                          &nbsp;Max rights for {name}
                          <Tooltip id="max-right-tooltip">
                            Users from external authentication provider won't be
                            able to have rights above max rights.
                            <br />
                            Max rights are used only in "initial" right mode.
                          </Tooltip>
                        </div>
                        <label
                          className="d-flex flex-row align-items-center"
                          style={{ marginTop: "1.5rem" }}
                        >
                          <span
                            style={{
                              marginRight: "1rem",
                            }}
                          >
                            Max tenant right
                          </span>
                          <Select
                            value={
                              maxTenantRight
                                ? {
                                    label: maxTenantRight,
                                    value: maxTenantRight,
                                  }
                                : { label: "Admin", value: "Admin" }
                            }
                            options={
                              [{ label: "None", value: "None" }].concat(
                                Object.keys(TLevel).map((l) => ({
                                  value: l,
                                  label: l,
                                }))
                              ) as any
                            }
                            styles={customStyles}
                            onChange={(selected) => {
                              dispatch({
                                type: EventType.SetMaxTenantLevel,
                                tenant: name,
                                level: (selected?.value
                                  ? selected.value
                                  : undefined) as TLevel,
                              });
                            }}
                          />
                        </label>
                        <label
                          className="d-flex flex-row align-items-center"
                          style={{ marginTop: "1.5rem" }}
                        >
                          <span
                            style={{
                              marginRight: "1rem",
                            }}
                          >
                            Max project right
                          </span>
                          <Select
                            value={
                              maxProjectRight
                                ? {
                                    label: maxProjectRight,
                                    value: maxProjectRight,
                                  }
                                : { label: "Admin", value: "Admin" }
                            }
                            options={
                              [{ label: "None", value: "None" }].concat(
                                Object.keys(TProjectLevel).map((l) => ({
                                  value: l,
                                  label: l,
                                }))
                              ) as any
                            }
                            styles={customStyles}
                            onChange={(selected) => {
                              dispatch({
                                type: EventType.SetMaxProjectLevel,
                                tenant: name,
                                level: (selected?.value
                                  ? selected.value
                                  : undefined) as TProjectLevel,
                              });
                            }}
                          />
                        </label>
                        <label
                          className="d-flex flex-row align-items-center"
                          style={{ marginTop: "1.5rem" }}
                        >
                          <span
                            style={{
                              marginRight: "1rem",
                            }}
                          >
                            Max key right
                          </span>
                          <Select
                            value={
                              maxKeyRight
                                ? {
                                    label: maxKeyRight,
                                    value: maxKeyRight,
                                  }
                                : { label: "Admin", value: "Admin" }
                            }
                            options={
                              [{ label: "None", value: "None" }].concat(
                                Object.keys(TLevel).map((l) => ({
                                  value: l,
                                  label: l,
                                }))
                              ) as any
                            }
                            styles={customStyles}
                            onChange={(selected) => {
                              dispatch({
                                type: EventType.SetMaxKeyLevel,
                                tenant: name,
                                level: (selected?.value
                                  ? selected.value
                                  : undefined) as TLevel,
                              });
                            }}
                          />
                        </label>
                        <label
                          className="d-flex flex-row align-items-center"
                          style={{ marginTop: "1.5rem" }}
                        >
                          <span
                            style={{
                              marginRight: "1rem",
                            }}
                          >
                            Max webhook right
                          </span>
                          <Select
                            value={
                              maxWebhookRight
                                ? {
                                    label: maxWebhookRight,
                                    value: maxWebhookRight,
                                  }
                                : { label: "Admin", value: "Admin" }
                            }
                            options={
                              [{ label: "None", value: "None" }].concat(
                                Object.keys(TLevel).map((l) => ({
                                  value: l,
                                  label: l,
                                }))
                              ) as any
                            }
                            styles={customStyles}
                            onChange={(selected) => {
                              dispatch({
                                type: EventType.SetMaxWebhookLevel,
                                tenant: name,
                                level: (selected?.value
                                  ? selected.value
                                  : undefined) as TLevel,
                              });
                            }}
                          />
                        </label>
                      </div>
                    )}
                  </div>
                </>
              );
            }
          )}
          {creating && (
            <>
              <label className="mt-3">Tenant</label>
              <ItemSelector
                label="New tenant"
                choices={selectorChoices}
                userRight={TLevel.Read}
                onItemChange={(item) => {
                  setCreating(false);
                  dispatch({ type: EventType.SelectTenant, name: item });
                }}
                onClear={() => {
                  setCreating(false);
                }}
                possibleRights={TLevelWithNoneArray}
              />
            </>
          )}
        </>
        {!creating &&
          selectedTenants.length < tenants.length &&
          !(
            props.tenant &&
            defaultValue?.tenants &&
            props.tenant in defaultValue.tenants
          ) && (
            <button
              className="btn btn-primary mt-2"
              onClick={() => {
                if (props.tenant) {
                  setCreating(false);
                  dispatch({
                    type: EventType.SelectTenant,
                    name: props.tenant,
                  });
                } else {
                  setCreating(true);
                }
              }}
            >
              {props.tenant
                ? `Add specific ${props.tenant} right`
                : "Add tenant right"}
            </button>
          )}
      </div>
    );
  } else {
    // TODO
    return <div>Failed to load tenants</div>;
  }
}

function ProjectSelector(props: {
  tenant: string;
  dispatch: (event: EventTypes) => void;
  projects: { name: string; level?: TProjectLevel }[];
}) {
  const [creating, setCreating] = useState(false);
  const { tenant, dispatch, projects } = props;

  const { user } = useContext(IzanamiContext);
  const { admin, rights } = user!;

  const projectQuery = useQuery({
    queryKey: [tenantQueryKey(tenant)],

    queryFn: () => queryTenant(tenant),
  });

  if (projectQuery.isLoading) {
    return <Loader message="Loading..." />;
  } else if (projectQuery.data?.projects?.length === 0) {
    return (
      <div>
        <em>No project defined yet</em>
      </div>
    );
  } else if (projectQuery.data) {
    const selectedProjectNames = projects.map((p) => p.name);
    const availableProjects = (
      projectQuery.data.projects?.map((p) => p.name) || []
    ).filter((p) => !selectedProjectNames.includes(p));

    return (
      <>
        {projects.map(({ name, level }) => (
          <div className="my-2" key={`${name}-container`}>
            <ItemSelector
              label={`${tenant} project`}
              key={name}
              userRight={
                admin || findTenantRight(rights, tenant) === TLevel.Admin
                  ? TLevel.Admin
                  : findProjectRight(rights, tenant, name) || TLevel.Read
              }
              choices={availableProjects}
              onItemChange={(item) => {
                dispatch({ type: "DeleteProject", name, tenant });
                dispatch({ type: "SelectProject", name: item, tenant });
              }}
              level={level}
              onLevelChange={(level) => {
                dispatch({
                  type: "SetProjectLevel",
                  name,
                  tenant,
                  level,
                });
              }}
              name={name}
              onClear={() => {
                dispatch({ type: "DeleteProject", tenant, name });
              }}
              possibleRights={TProjectLevelArray}
            />
          </div>
        ))}
        {creating && (
          <div className="my-2">
            <ItemSelector
              label={`${tenant} new project`}
              choices={availableProjects}
              userRight={TLevel.Read}
              onItemChange={(project) => {
                setCreating(false);
                dispatch({ type: "SelectProject", name: project, tenant });
              }}
              onClear={() => setCreating(false)}
              possibleRights={TProjectLevelArray}
            />
          </div>
        )}
        {!creating &&
          projects.length < (projectQuery.data.projects?.length || 0) && (
            <button
              className="btn btn-primary btn-sm mt-2"
              onClick={() => setCreating(true)}
            >
              Add project rights
            </button>
          )}
      </>
    );
  } else {
    return <div>Error while loading projects</div>;
  }
}

function KeySelector(props: {
  tenant: string;
  dispatch: (event: EventTypes) => void;
  keys: { name: string; level?: TLevel }[];
}) {
  const [creating, setCreating] = useState(false);
  const { tenant, dispatch, keys } = props;

  const keyQuery = useQuery({
    queryKey: [tenantKeyQueryKey(tenant)],

    queryFn: () => queryKeys(tenant),
  });
  const { user } = useContext(IzanamiContext);
  const { admin, rights } = user!;

  if (keyQuery.isLoading) {
    return <Loader message="Loading..." />;
  } else if (keyQuery.data?.length === 0) {
    return (
      <div>
        <em>No keys defined yet</em>
      </div>
    );
  } else if (keyQuery.data) {
    const selectedKeyNames = keys.map(({ name }) => name);
    const availableKeys = (keyQuery.data.map((k) => k.name) || []).filter(
      (p) => !selectedKeyNames.includes(p)
    );
    return (
      <>
        {keys.map(({ name, level }) => (
          <div className="my-2" key={`${name}-container`}>
            <ItemSelector
              label={`${tenant} key`}
              key={name}
              choices={availableKeys}
              onItemChange={(item) => {
                dispatch({ type: "DeleteKey", name, tenant });
                dispatch({ type: "SelectKey", name: item, tenant });
              }}
              level={level}
              userRight={
                admin || findTenantRight(rights, tenant) === TLevel.Admin
                  ? TLevel.Admin
                  : findKeyRight(rights, tenant, name) || TLevel.Read
              }
              onLevelChange={(level) => {
                dispatch({
                  type: "SetKeyLevel",
                  name,
                  tenant,
                  level,
                });
              }}
              name={name}
              onClear={() => {
                dispatch({ type: "DeleteKey", tenant, name });
              }}
              possibleRights={TLevelArray}
            />
          </div>
        ))}
        {creating && (
          <div className="my-2">
            <ItemSelector
              label={`${tenant} new key`}
              choices={availableKeys}
              onItemChange={(project) => {
                setCreating(false);
                dispatch({ type: "SelectKey", name: project, tenant });
              }}
              onClear={() => setCreating(false)}
              userRight={TLevel.Read}
              possibleRights={TLevelArray}
            />
          </div>
        )}
        {!creating && keys.length < (keyQuery.data.length || 0) && (
          <button
            className="btn btn-primary btn-sm"
            onClick={() => setCreating(true)}
          >
            Add key rights
          </button>
        )}
      </>
    );
  } else {
    return <div>Error while loading projects</div>;
  }
}

function WebhookSelector(props: {
  tenant: string;
  dispatch: (event: EventTypes) => void;
  webhooks: { name: string; level?: TLevel }[];
}) {
  const [creating, setCreating] = useState(false);
  const { tenant, dispatch, webhooks } = props;

  const webhookQuery = useQuery({
    queryKey: [webhookQueryKey(tenant)],

    queryFn: () => fetchWebhooks(tenant),
  });
  const { user } = useContext(IzanamiContext);
  const { admin, rights } = user!;

  if (webhookQuery.isLoading) {
    return <Loader message="Loading..." />;
  } else if (webhookQuery.data?.length === 0) {
    return (
      <div>
        <em>No webhooks defined yet</em>
      </div>
    );
  } else if (webhookQuery.data) {
    const selectedWebhookNames = webhooks.map(({ name }) => name);
    const availableWebhooks = (
      webhookQuery.data.map((k) => k.name) || []
    ).filter((p) => !selectedWebhookNames.includes(p));
    return (
      <>
        {webhooks.map(({ name, level }) => (
          <div className="my-2" key={`${name}-container`}>
            <ItemSelector
              label={`${tenant} key`}
              key={name}
              choices={availableWebhooks}
              onItemChange={(item) => {
                dispatch({ type: "DeleteWebhook", name, tenant });
                dispatch({ type: "SelectWebhook", name: item, tenant });
              }}
              level={level}
              userRight={
                admin || findTenantRight(rights, tenant) === TLevel.Admin
                  ? TLevel.Admin
                  : findKeyRight(rights, tenant, name) || TLevel.Read
              }
              onLevelChange={(level) => {
                dispatch({
                  type: "SetWebhookLevel",
                  name,
                  tenant,
                  level,
                });
              }}
              name={name}
              onClear={() => {
                dispatch({ type: "DeleteWebhook", tenant, name });
              }}
              possibleRights={TLevelArray}
            />
          </div>
        ))}
        {creating && (
          <div className="my-2">
            <ItemSelector
              label={`${tenant} new key`}
              choices={availableWebhooks}
              onItemChange={(project) => {
                setCreating(false);
                dispatch({ type: "SelectWebhook", name: project, tenant });
              }}
              onClear={() => setCreating(false)}
              userRight={TLevel.Read}
              possibleRights={TLevelArray}
            />
          </div>
        )}
        {!creating && webhooks.length < (webhookQuery.data.length || 0) && (
          <button
            className="btn btn-primary btn-sm"
            onClick={() => setCreating(true)}
          >
            Add webhook rights
          </button>
        )}
      </>
    );
  } else {
    return <div>Error while loading webhooks</div>;
  }
}

type ItemSelectorProps<T extends TProjectLevelWithNone> = {
  choices: string[];
  onItemChange: (name: string) => void;
  onClear?: () => void;
  name?: string;
  rightOnly?: boolean;
  label: string;
  level?: T;
  onLevelChange?: (level: T) => void;
  userRight: T;
  possibleRights: T[];
};

function ItemSelector<T extends TProjectLevelWithNone>(
  props: ItemSelectorProps<T>
) {
  const {
    choices,
    onItemChange,
    onLevelChange,
    onClear,
    name,
    level,
    userRight,
    label,
    possibleRights,
  } = props;

  const baseAriaLabel = `${label}${name ? ` ${name}` : ""}`;

  return (
    <div className="right-selector">
      <Select
        styles={customStyles}
        className="right-name-selector"
        value={{ label: name, value: name }}
        options={choices.map((c) => ({ label: c, value: c }))}
        onChange={(entry) => {
          onItemChange(entry!.value!);
        }}
        isDisabled={!!props.rightOnly}
        aria-label={baseAriaLabel}
      />
      <Select
        aria-label={`${baseAriaLabel} right level`}
        styles={customStyles}
        value={level ? { label: level, value: level } : undefined}
        placeholder="Right level"
        options={possibleRights
          .filter((t) => isRightBelowOrEqual(t, userRight))
          .map((level) => ({
            label: level,
            value: level,
          }))}
        onChange={(entry) => {
          onLevelChange?.(entry!.value! as any);
        }}
        isDisabled={!name}
      />
      <button
        type="button"
        className="btn btn-danger"
        onClick={() => onClear?.()}
      >
        Delete
      </button>
    </div>
  );
}
