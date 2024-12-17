import * as React from "react";
import { useContext, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Select from "react-select";
import {
  findKeyRight,
  findProjectRight,
  findTenantRight,
  rightsBelow,
  IzanamiContext,
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
import { TLevel, TRights } from "../utils/types";
import { Loader } from "./Loader";

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
  level: TLevel;
  name: string;
}

interface ProjectLevelEvent extends Event {
  type: "SetProjectLevel";
  level: TLevel;
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
  | WebhookDeleteEvent;

export type State = {
  name: string;
  level?: TLevel;
  projects: {
    name: string;
    level?: TLevel;
  }[];
  keys: {
    name: string;
    level?: TLevel;
  }[];
  webhooks: {
    name: string;
    level?: TLevel;
  }[];
}[];

function projectOrKeyArrayToObject(arr: { name: string; level?: TLevel }[]) {
  return arr.reduce((acc, { name, level }) => {
    acc[name] = { level };
    return acc;
  }, {} as { [x: string]: any });
}

export function rightStateArrayToBackendMap(state?: State): TRights {
  if (!state) {
    return { tenants: {} };
  }
  const backendRights = state.reduce(
    (acc, { name, level, projects, keys, webhooks }) => {
      acc[name] = {
        level,
        projects: projectOrKeyArrayToObject(projects),
        keys: projectOrKeyArrayToObject(keys),
        webhooks: projectOrKeyArrayToObject(webhooks),
      };
      return acc;
    },
    {} as { [x: string]: any }
  );

  return { tenants: backendRights };
}

const reducer = function reducer(state: State, event: EventTypes): State {
  switch (event.type) {
    case EventType.SetupState: {
      if (!event.rights.tenants) {
        return state;
      } else {
        return Object.entries(event.rights.tenants).map(([key, value]) => ({
          name: key,
          level: value.level,
          projects: Object.entries(value.projects).map(
            ([projectName, projectValue]) => ({
              name: projectName,
              level: projectValue.level,
            })
          ),
          keys: Object.entries(value.keys).map(([keyName, keyValue]) => ({
            name: keyName,
            level: keyValue.level,
          })),
          webhooks: Object.entries(value.webhooks).map(
            ([keyName, keyValue]) => ({
              name: keyName,
              level: keyValue.level,
            })
          ),
        }));
      }
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
          };
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
  }
};

export function isValid(state: State): boolean {
  return state.every(({ name, level, projects, keys }) => {
    return (
      name &&
      level &&
      projects.every(({ name, level }) => name && level) &&
      keys.every(({ name, level }) => name && level)
    );
  });
}

export function RightSelector(props: {
  defaultValue?: TRights;
  tenantLevelFilter?: TLevel;
  tenant?: string;
  onChange: (value: State) => void;
}) {
  const { defaultValue, tenantLevelFilter, onChange } = props;
  const tenantQuery = useQuery({
    queryKey: [MutationNames.TENANTS],

    queryFn: () => queryTenants(tenantLevelFilter),
  });

  const [state, dispatch] = React.useReducer(reducer, []);
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
  const [creating, setCreating] = useState(
    //selectedTenants.length === 0 && !defaultValue
    false
  );

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
          {state.map(({ name, level, projects, keys, webhooks }) => {
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
                      dispatch({ type: EventType.SetTenantLevel, level, name });
                    }}
                    onClear={() => {
                      dispatch({ type: EventType.DeleteTenant, name });
                    }}
                  />
                  <div className="sub_container sub_container-bglighter project-selector">
                    <label>
                      <i className="fas fa-building" aria-hidden></i>
                      &nbsp;Project rights for {name}
                    </label>
                    <div className="my-2">
                      <ProjectSelector
                        tenant={name}
                        dispatch={dispatch}
                        projects={projects}
                      />
                    </div>
                  </div>
                  <div className="sub_container sub_container-bglighter key-selector">
                    <label>
                      <i className="fas fa-key" aria-hidden></i>&nbsp;Keys
                      rights for {name}
                    </label>
                    <div className="my-2">
                      <KeySelector
                        tenant={name}
                        dispatch={dispatch}
                        keys={keys}
                      />
                    </div>
                  </div>
                  <div className="sub_container sub_container-bglighter key-selector">
                    <label>
                      <i className="fas fa-plug" aria-hidden></i>&nbsp;Webhooks
                      rights for {name}
                    </label>
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
            );
          })}
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
  projects: { name: string; level?: TLevel }[];
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
            />
          </div>
        ))}
        {creating && (
          <div className="my-2">
            <ItemSelector
              label="${tenant} new project"
              choices={availableProjects}
              userRight={TLevel.Read}
              onItemChange={(project) => {
                setCreating(false);
                dispatch({ type: "SelectProject", name: project, tenant });
              }}
              onClear={() => setCreating(false)}
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

function ItemSelector(props: {
  choices: string[];
  userRight?: TLevel;
  onItemChange: (name: string) => void;
  onLevelChange?: (level: TLevel) => void;
  onClear?: () => void;
  level?: TLevel;
  name?: string;
  rightOnly?: boolean;
  label: string;
}) {
  const {
    choices,
    onItemChange,
    onLevelChange,
    onClear,
    name,
    level,
    userRight,
    label,
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
        value={level ? { value: level, label: level } : undefined}
        placeholder="Right level"
        options={rightsBelow(userRight).map((level) => ({
          label: level,
          value: level,
        }))}
        onChange={(entry) => {
          onLevelChange?.(entry!.value!);
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
