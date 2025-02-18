import { format } from "date-fns";
import {
  Configuration,
  ImportRequest,
  isLightWasmFeature,
  IzanamiTenantExportRequest,
  IzanamiV1ImportRequest,
  LightWebhook,
  Mailer,
  MailerConfiguration,
  ProjectInCreationType,
  ProjectType,
  TagType,
  TCompleteFeature,
  TContext,
  TenantInCreationType,
  TenantType,
  TKey,
  TLevel,
  TLightFeature,
  TRights,
  TSingleRightForTenantUser,
  TTenantRight,
  TUser,
  TWasmConfig,
  WasmFeature,
  Webhook,
  SearchResult,
  FeatureTypeName,
  TClassicalCondition,
  PersonnalAccessToken,
  TokenTenantRight,
  OIDCSettings,
} from "./types";
import { isArray } from "lodash";
import toast from "react-hot-toast";
import * as React from "react";

export enum MutationNames {
  TENANTS = "TENANTS",
  USERS = "USER",
  CONFIGURATION = "CONFIGURATION",
}

export function projectLogQueryKey(tenant: string, project: string): string {
  return `${tenant}/${project}/logs`;
}

export function webhookQueryKey(tenant: string): string {
  return `${tenant}-webhooks`;
}

export function tenantScriptKey(tenant: string): string {
  return `${tenant}-wasm-scripts`;
}

export function tenantFeaturesKey(tenant: string): string {
  return `TENANT-FEATURES-${tenant}`;
}

export function globalContextKey(tenant: string): string {
  return `GLOBAL-${tenant}-contexts`;
}

export function projectContextKey(tenant: string, project: string): string {
  return `PROJECT-${tenant}-${project}-contexts`;
}

export function mailerQueryKey(mailer: Mailer): string {
  return `MAILER-${mailer}`;
}

export function tenantQueryKey(tenant: string): string {
  return `TENANT-${tenant}`;
}

export function projectQueryKey(tenant: string, project: string): string {
  return `PROJECT-${tenant}-${project}`;
}

export function tagQueryKey(tenant: string, tag: string): string {
  return `TAG-${tenant}-${tag}`;
}

export function tagsQueryKey(tenant: string): string {
  return `TAG-${tenant}`;
}
export function projectsQueryKey(tenant: string): string {
  return `PROJECT-${tenant}`;
}

export function featureQueryKey(tenant: string, tag: string): string {
  return `FEATURES-${tenant}-${tag}`;
}

export function tenantKeyQueryKey(tenant: string) {
  return `TENANT-${tenant}-KEYS`;
}

export function userQueryKey(user: string) {
  return `USER-${user}`;
}

export function userQueryKeyForTenant(user: string, tenant: string) {
  return `${userQueryKey(user)}-${tenant}`;
}

export function tenantUserQueryKey(tenant: string) {
  return `USERS-${tenant}`;
}

export function projectUserQueryKey(tenant: string, project: string) {
  return `USERS-${tenant}-${project}`;
}

export function webhookUserQueryKey(tenant: string, webhook: string) {
  return `USERS-${tenant}-${webhook}`;
}

export function keyUserQueryKey(tenant: string, key: string) {
  return `USERS-${tenant}-${key}`;
}

export function personnalAccessTokenKey(user: string) {
  return `PAC-${user}`;
}

export function queryPersonnalAccessTokens(
  user: string
): Promise<PersonnalAccessToken[]> {
  return handleFetchJsonResponse(fetch(`/api/admin/users/${user}/tokens`)).then(
    (t) => {
      const tokens: PersonnalAccessToken[] = t;
      return tokens.map((t) => {
        let res = { ...t };
        if ("expiresAt" in res) {
          res.expiresAt = new Date(res.expiresAt);
        }
        res.createdAt = new Date(res.createdAt);
        return res;
      });
    }
  );
}

export function createPersonnalAccessToken(
  user: string,
  name: string,
  expiration: Date,
  timezone: string,
  allRights: boolean,
  rights: { [tenant: string]: TokenTenantRight[] }
) {
  const hasExpiration = expiration && !isNaN(expiration?.getTime());
  return handleFetchJsonResponse(
    fetch(`/api/admin/users/${user}/tokens`, {
      method: "POST",
      body: JSON.stringify({
        name: name,
        expiresAt: hasExpiration
          ? format(expiration, "yyyy-MM-dd'T'HH:mm")
          : undefined,
        expirationTimezone: hasExpiration ? timezone : undefined,
        allRights,
        rights,
      }),
      headers: {
        "content-type": "application/json",
      },
    })
  );
}

export function updatePersonnalAccessToken(token: PersonnalAccessToken) {
  const hasExpiration =
    "expiresAt" in token && !isNaN(token.expiresAt?.getTime());
  return handleFetchJsonResponse(
    fetch(`/api/admin/users/${token.username}/tokens/${token.id}`, {
      method: "PUT",
      body: JSON.stringify({
        ...token,
        expirationTimezone: hasExpiration
          ? token.expirationTimezone
          : undefined,
        expiresAt: hasExpiration
          ? format(token.expiresAt, "yyyy-MM-dd'T'HH:mm")
          : undefined,
      }),
      headers: {
        "content-type": "application/json",
      },
    })
  );
}

export function deletePersonnalAccessToken(user: string, id: string) {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/users/${user}/tokens/${id}`, {
      method: "DELETE",
    })
  );
}

export function importData(
  tenant: string,
  importRequest: ImportRequest
): Promise<{ messages: string[]; conflicts?: object[] }> {
  const data = new FormData();
  data.append("export", importRequest.file.item(0)!);
  return handleFetchJsonResponse(
    fetch(
      `/api/admin/tenants/${tenant}/_import?version=2&conflict=${importRequest.conflictStrategy}`,
      {
        method: "POST",
        body: data,
      }
    ).then((res) => {
      if (res.status === 409) {
        return res.json().then((json) => {
          return {
            json: () => Promise.resolve(json),
            status: 200,
          } as any;
        });
      }
      return res;
    })
  );
}

export function requestExport(
  tenant: string,
  exportRequest: IzanamiTenantExportRequest
) {
  return fetch(`/api/admin/tenants/${tenant}/_export`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
    },
    body: JSON.stringify(exportRequest),
  })
    .then((resp) => resp.blob())
    .then((blob) => {
      const objectUrl = URL.createObjectURL(blob);
      const link: HTMLAnchorElement = document.createElement("a");
      link.href = objectUrl;
      link.download = "export.ndjson"; // the default filename when the user saves the file
      link.click();
      URL.revokeObjectURL(objectUrl);
    });
}

export function queryTenantUsers(tenant: string): Promise<
  {
    username: string;
    email: string;
    admin: boolean;
    userType: "INTERNAL" | "OIDC" | "OTOROSHI";
    right: TLevel;
  }[]
> {
  return handleFetchJsonResponse(fetch(`/api/admin/tenants/${tenant}/users`));
}

export function findFeatures(
  tenant: string,
  search = ""
): Promise<TLightFeature[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/features?search=${search}`)
  );
}

export function searchFeatures(
  tenant: string,
  projects: string[],
  allTagsIn: string[] = [],
  oneTagIn: string[] = [],
  noTagIn: string[] = []
): Promise<TLightFeature[]> {
  return handleFetchJsonResponse(
    fetch(
      `/api/admin/tenants/${tenant}/features?allTagsIn=${allTagsIn.join(
        ","
      )}&oneTagIn=${oneTagIn.join(",")}&noTagIn=${noTagIn.join(",")}`
    )
  );
}

export function queryWebhookUsers(
  tenant: string,
  webhook: string
): Promise<TSingleRightForTenantUser[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/webhooks/${webhook}/users`)
  );
}

export function queryProjectUsers(
  tenant: string,
  project: string
): Promise<TSingleRightForTenantUser[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${project}/users`)
  );
}

export function queryTenants(
  tenantLevelFilter?: TLevel
): Promise<TenantType[]> {
  return handleFetchJsonResponse(
    fetch(
      `/api/admin/tenants${
        tenantLevelFilter ? `?right=${tenantLevelFilter}` : ""
      }`
    )
  );
}

export function queryTenant(tenant: string): Promise<TenantType> {
  return handleFetchJsonResponse(fetch(`/api/admin/tenants/${tenant}`));
}

export function queryProjects(tenant: string): Promise<ProjectType[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/projects`)
  );
}
export function queryUser(user: string): Promise<TUser> {
  return handleFetchJsonResponse(fetch(`/api/admin/users/${user}`));
}

export function queryUserForTenant(
  user: string,
  tenant: string
): Promise<TUser> {
  return handleFetchJsonResponse(fetch(`/api/admin/${tenant}/users/${user}`));
}

export function queryContextsForProject(
  tenant: string,
  project: string
): Promise<TContext[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${project}/contexts`)
  ).then((contexts) => {
    contexts.forEach(parseOverloadDates);
    return contexts;
  });
}

export function queryGlobalContexts(
  tenant: string,
  all = false
): Promise<TContext[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/contexts?all=${"" + all}`)
  ).then((contexts) => {
    contexts.forEach(parseOverloadDates);
    return contexts;
  });
}

function parseOverloadDates(context: TContext) {
  context?.overloads?.forEach((ctx) => castDateIfNeeded(ctx));
  context.children.forEach(parseOverloadDates);
}

export function queryUserForTenants(
  user: string,
  tenants: string[]
): Promise<TUser> {
  return Promise.all(
    tenants.map((tenant) =>
      handleFetchJsonResponse(fetch(`/api/admin/${tenant}/users/${user}`))
    )
  ).then((users: TUser[]) => {
    const rights = users
      .map((u) => u.rights.tenants)
      .reduce((acc, next) => {
        return { ...acc, ...next };
      }, {});

    return { ...users[0], rights: { tenants: rights } } as TUser;
  });
}

export function createTenant(
  tenant: TenantInCreationType
): Promise<TenantType> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(tenant),
    })
  );
}

export function updateTenant(
  oldName: string,
  tenant: { name: string; description: string }
): Promise<any> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${oldName}`, {
      method: "PUT",
      body: JSON.stringify(tenant),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function createTag(
  tenant: string,
  name: string,
  description: string
): Promise<TenantType> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/tags`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ name, description }),
    })
  );
}

export function createProject(
  tenant: string,
  project: ProjectInCreationType
): Promise<ProjectType> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/projects`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(project),
    })
  );
}

export function updateProject(
  tenant: string,
  oldName: string,
  project: ProjectInCreationType
): Promise<any> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${oldName}`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(project),
    })
  );
}

export function queryProject(tenant: string, id: string): Promise<ProjectType> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${id}`)
  ).then((json: ProjectType) => {
    json?.features?.forEach((feat) => castDateIfNeeded(feat));

    return json;
  });
}

export function queryProjectById(
  tenant: string,
  id: string
): Promise<ProjectType | undefined> {
  return fetch(`/api/admin/tenants/${tenant}/projects/_id/${id}`).then(
    (resp) => {
      if (resp.status >= 400) {
        return Promise.resolve(undefined);
      } else {
        return resp.json();
      }
    }
  );
}

export function updateFeature(
  tenant: string,
  id: string,
  feature: TCompleteFeature
): Promise<TCompleteFeature> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/features/${id}`, {
      method: "PUT",
      body: JSON.stringify(feature),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function updateFeatureActivationForContext(
  tenant: string,
  project: string,
  path: string,
  feature: string,
  enabled: boolean,
  resultType: FeatureTypeName,
  conditions?: TClassicalCondition[],
  wasmConfig?: TWasmConfig,
  value?: string | boolean | number
) {
  return handleFetchWithoutResponse(
    fetch(
      `/api/admin/tenants/${tenant}/projects/${project}/contexts/${path}/features/${feature}`,
      {
        method: "PUT",
        body: JSON.stringify({
          enabled,
          conditions,
          feature,
          wasmConfig,
          value: value,
          resultType,
        }),
        headers: {
          "Content-Type": "application/json",
        },
      }
    )
  );
}

export function createGlobalContext(
  tenant: string,
  path: string,
  name: string
) {
  return handleFetchWithoutResponse(
    fetch(
      `/api/admin/tenants/${tenant}/contexts${
        path.length > 0 ? `/${path}` : ""
      }`,
      {
        method: "POST",
        body: JSON.stringify({ name }),
        headers: {
          "Content-Type": "application/json",
        },
      }
    )
  );
}

export function createContext(
  tenant: string,
  project: string,
  path: string,
  name: string
) {
  return handleFetchWithoutResponse(
    fetch(
      `/api/admin/tenants/${tenant}/projects/${project}/contexts${
        path.length > 0 ? `${path.startsWith("/") ? "" : "/"}${path}` : ""
      }`,
      {
        method: "POST",
        body: JSON.stringify({ name }),
        headers: {
          "Content-Type": "application/json",
        },
      }
    )
  );
}

export function deleteFeatureActivationForContext(
  tenant: string,
  project: string,
  path: string,
  feature: string
) {
  return handleFetchWithoutResponse(
    fetch(
      `/api/admin/tenants/${tenant}/projects/${project}/contexts/${path}/features/${feature}`,
      {
        method: "DELETE",
      }
    )
  );
}

export function deleteContext(tenant: string, project: string, path: string) {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${project}/contexts/${path}`, {
      method: "DELETE",
    })
  );
}

export function deleteGlobalContext(tenant: string, path: string) {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/contexts/${path}`, {
      method: "DELETE",
    })
  );
}

export function createFeature(
  tenant: string,
  project: string,
  feature: any
): Promise<TCompleteFeature> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${project}/features`, {
      method: "POST",
      body: JSON.stringify(feature),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function toCompleteFeature(
  tenant: string,
  feature: TLightFeature
): Promise<TCompleteFeature> {
  if (isLightWasmFeature(feature)) {
    return fetchWasmConfig(tenant, feature.wasmConfig).then((wasmConfig) => {
      return { ...feature, wasmConfig } as WasmFeature;
    });
  } else {
    return Promise.resolve(feature);
  }
}

function fetchWasmConfig(tenant: string, script: string): Promise<TWasmConfig> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/local-scripts/${script}`, {
      method: "GET",
    })
  );
}

export function testFeature(
  tenant: string,
  feature: TCompleteFeature,
  user: string,
  date: Date,
  payload?: { [x: string]: any }
): Promise<{ active: boolean }> {
  return handleFetchJsonResponse(
    fetch(
      `/api/admin/tenants/${tenant}/test?date=${encodeURIComponent(
        format(date, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
      )}&user=${user}`,
      {
        method: "POST",
        body: JSON.stringify({ feature, payload }),
        headers: {
          "Content-Type": "application/json",
        },
      }
    )
  );
}

export function testExistingFeature(
  tenant: string,
  featureId: string,
  date: Date,
  context: string,
  user: string,
  payload?: string
): Promise<{ active: boolean }> {
  return handleFetchJsonResponse(
    fetch(
      `/api/admin/tenants/${tenant}/features/${featureId}/test${
        context ? "/" + context : ""
      }?date=${encodeURIComponent(
        format(date, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
      )}&user=${user}`,
      {
        method: "POST",
        body: payload ? payload : "{}",
        headers: { "Content-Type": "application/json" },
      }
    )
  );
}

export function deleteFeature(tenant: string, id: string): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/features/${id}`, {
      method: "DELETE",
    })
  );
}

export function deleteProject(
  tenant: string,
  name: string,
  password: string
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${name}`, {
      method: "DELETE",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ password }),
    })
  );
}

export function deleteTenant(
  password: string,
  name: string
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${name}`, {
      method: "DELETE",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ password }),
    })
  );
}

export function deleteTag(tenant: string, name: string): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/tags/${name}`, {
      method: "DELETE",
    })
  );
}

export function queryTag(tenant: string, name: string): Promise<TagType> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/tags/${name}`)
  );
}

export function queryTags(tenant: string): Promise<TagType[]> {
  return handleFetchJsonResponse(fetch(`/api/admin/tenants/${tenant}/tags`));
}
export function updateTag(
  tenant: string,
  tag: TagType,
  currentName: string
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/tags/${currentName}`, {
      method: "PUT",
      body: JSON.stringify(tag),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function queryConfiguration(): Promise<Configuration> {
  return handleFetchJsonResponse(fetch(`/api/admin/configuration`)).then(
    ({ anonymousReportingLastAsked, ...rest }) => ({
      ...rest,
      anonymousReportingLastAsked: anonymousReportingLastAsked
        ? new Date(anonymousReportingLastAsked)
        : undefined,
    })
  );
}

export function queryStats(): Promise<object> {
  return handleFetchJsonResponse(fetch(`/api/admin/stats`));
}

export function updateConfiguration(
  configuration: Omit<Configuration, "version" | "preventOAuthModification">
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch("/api/admin/configuration", {
      method: "PUT",
      body: JSON.stringify(configuration),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function queryMailerConfiguration(
  id: Mailer
): Promise<MailerConfiguration> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/configuration/mailer/${id}`)
  );
}

export function updateMailerConfiguration(
  mailer: Mailer,
  configuration: MailerConfiguration
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/configuration/mailer/${mailer}`, {
      method: "PUT",
      body: JSON.stringify(configuration),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function queryKeys(tenant: string): Promise<TKey[]> {
  return handleFetchJsonResponse(fetch(`/api/admin/tenants/${tenant}/keys`));
}

export function deleteKey(
  tenant: string,
  name: string,
  password: string
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/keys/${name}`, {
      method: "DELETE",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ password }),
    })
  );
}

export function updateKey(
  tenant: string,
  oldName: string,
  newKey: TKey
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/keys/${oldName}`, {
      method: "PUT",
      body: JSON.stringify(newKey),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function updateUserPassword(
  name: string,
  user: {
    oldPassword: string;
    password: string;
  }
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/users/${name}/password`, {
      method: "PUT",
      body: JSON.stringify(user),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function updateUserInformation(
  name: string,
  user: {
    username: string;
    email: string;
    password: string;
    defaultTenant?: string;
  }
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/users/${name}`, {
      method: "PUT",
      body: JSON.stringify(user),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function updateUserRights(
  name: string,
  user: {
    admin: boolean;
    rights: TRights;
  }
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/users/${name}/rights`, {
      method: "PUT",
      body: JSON.stringify(user),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function patchFeatures(
  tenant: string,
  patches: { op: string; path: string; value?: any }[]
) {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/features`, {
      method: "PATCH",
      body: JSON.stringify(patches),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function updateUserRightsForProject(
  username: string,
  tenant: string,
  project: string,
  right?: TLevel
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(
      `/api/admin/tenants/${tenant}/projects/${project}/users/${username}/rights`,
      {
        method: "PUT",
        body: right ? JSON.stringify({ level: right }) : "{}",
        headers: {
          "Content-Type": "application/json",
        },
      }
    )
  );
}

export function updateUserRightsForWebhook(
  username: string,
  tenant: string,
  webhook: string,
  right?: TLevel
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(
      `/api/admin/tenants/${tenant}/webhook/${webhook}/users/${username}/rights`,
      {
        method: "PUT",
        body: right ? JSON.stringify({ level: right }) : "{}",
        headers: {
          "Content-Type": "application/json",
        },
      }
    )
  );
}

export function inviteUsersToProject(
  tenant: string,
  project: string,
  users: string[],
  level: TLevel
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${project}/users`, {
      method: "POST",
      body: JSON.stringify(users.map((u) => ({ username: u, level }))),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function inviteUsersToTenant(
  tenant: string,
  users: string[],
  level: TLevel
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/users`, {
      method: "POST",
      body: JSON.stringify(users.map((u) => ({ username: u, level }))),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function updateUserRightsForTenant(
  name: string,
  tenant: string,
  right: TTenantRight
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/${tenant}/users/${name}/rights`, {
      method: "PUT",
      body: right ? JSON.stringify(right) : "{}",
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function createKey(tenant: string, key: TKey): Promise<TKey> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/keys`, {
      method: "POST",
      body: JSON.stringify(key),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function createUser(
  username: string,
  password: string,
  token: string
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch("/api/admin/users", {
      method: "POST",
      body: JSON.stringify({ username, password, token }),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function queryTagFeatures(
  tenant: string,
  name: string
): Promise<TLightFeature[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/features?tag=${name}`)
  ).then((features: TLightFeature[]) => {
    features?.forEach((feat) => castDateIfNeeded(feat));
    return features;
  });
}

export function castDateIfNeeded(feat: any): void {
  if (isArray(feat?.conditions)) {
    feat?.conditions?.map((cond: any) => {
      if (cond?.period?.begin) {
        cond.period.begin = new Date(cond.period.begin);
      }

      if (cond?.period?.end) {
        cond.period.end = new Date(cond.period.end);
      }
    });
  } else if (feat?.conditions) {
    if (feat.conditions.begin) {
      feat.conditions.begin = new Date(feat.conditions.begin);
    }
    if (feat.conditions.end) {
      feat.conditions.end = new Date(feat.conditions.end);
    }
  }

  if (feat?.stale?.since) {
    feat.stale.since = new Date(feat.stale.since);
  }
}

export function fetchWasmScripts(tenant: string): Promise<
  {
    config: TWasmConfig;
    features: { name: string; id: string; project: string }[];
  }[]
> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/local-scripts?features=true`)
  );
}

export function deleteScript(tenant: string, name: string): Promise<any> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/local-scripts/${name}`, {
      method: "DELETE",
    })
  );
}

export function updateScript(
  tenant: string,
  name: string,
  script: TWasmConfig
): Promise<any> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/local-scripts/${name}`, {
      method: "PUT",
      body: JSON.stringify(script),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

function _handleFetchResponse<T>(
  request: Promise<Response>,
  handler: (resp: Response) => Promise<T>
): Promise<T> {
  return request
    .then((response) => {
      if (response.status >= 400) {
        return response.json().then((body) => Promise.reject(body));
      }
      return handler(response);
    })
    .catch((error) => {
      const id = crypto.randomUUID();
      const msg =
        error instanceof String
          ? error
          : error?.message
          ? error.message
          : JSON.stringify(error);
      toast.error(
        <div
          style={{
            display: "flex",
            alignItems: "center",
          }}
        >
          {msg}
          <button
            onClick={() => toast.dismiss(id)}
            className="btn btn-sm ms-3 me-0"
          >
            X
          </button>
        </div>,
        {
          id: id,
          icon: <i className="fa-solid fa-circle-exclamation" aria-hidden></i>,
        }
      );

      throw error;
    });
}

export function handleFetchJsonResponse(
  request: Promise<Response>
): Promise<any> {
  return _handleFetchResponse(request, (response) => response.json());
}

export function handleFetchWithoutResponse(
  request: Promise<Response>
): Promise<undefined> {
  return _handleFetchResponse(request, () => Promise.resolve(undefined));
}

export function deleteUser(user: string): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/users/${user}`, {
      method: "DELETE",
    })
  );
}

export function createInvitation(
  email: string,
  admin: boolean,
  rights: TRights
): Promise<{ invitationUrl?: string } | null> {
  return fetch(`/api/admin/invitation`, {
    method: "POST",
    body: JSON.stringify({ email, admin, rights }),
    headers: {
      "Content-Type": "application/json",
    },
  }).then((response) => {
    if (response.status === 201) {
      return response.json();
    } else if (response.status === 204) {
      return null;
    }
  });
}

export function fetchWebhooks(tenant: string): Promise<Webhook[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/webhooks`)
  );
}

export function deleteWebhook(
  tenant: string,
  id: string,
  password: string
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/webhooks/${id}`, {
      method: "DELETE",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ password }),
    })
  );
}

export function updateWebhook(
  tenant: string,
  id: string,
  webhook: LightWebhook
): Promise<void> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/webhooks/${id}`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(webhook),
    })
  );
}

export function createWebhook(
  tenant: string,
  webhook: LightWebhook
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/webhooks`, {
      method: "POST",
      body: JSON.stringify(webhook),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function fetchWebhookUsers(
  tenant: string,
  webhook: string
): Promise<TSingleRightForTenantUser[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/webhooks/${webhook}/users`)
  );
}

export function fetchKeyUsers(
  tenant: string,
  key: string
): Promise<TSingleRightForTenantUser[]> {
  return handleFetchJsonResponse(
    fetch(`/api/admin/tenants/${tenant}/keys/${key}/users`)
  );
}

export function usersQuery(): Promise<TUser[]> {
  return handleFetchJsonResponse(fetch(`/api/admin/users`));
}

export function updateWebhookRightsFor(
  tenant: string,
  webhook: string,
  user: string,
  right?: TLevel
): Promise<void> {
  return handleFetchWithoutResponse(
    fetch(
      `/api/admin/tenants/${tenant}/webhooks/${webhook}/users/${user}/rights`,
      {
        method: "PUT",
        body: right ? JSON.stringify({ level: right }) : "{}",
        headers: {
          "Content-Type": "application/json",
        },
      }
    )
  );
}

export function updateKeyRightsFor(
  tenant: string,
  key: string,
  user: string,
  right?: TLevel
): Promise<void> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/keys/${key}/users/${user}/rights`, {
      method: "PUT",
      body: right ? JSON.stringify({ level: right }) : "{}",
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function importIzanamiV1Data(
  importRequest: IzanamiV1ImportRequest
): Promise<any> {
  const data = new FormData();
  data.append("features", importRequest.featureFiles.item(0)!);
  data.append("users", importRequest.userFiles.item(0)!);
  data.append("keys", importRequest.keyFiles.item(0)!);
  data.append("scripts", importRequest.scriptFiles.item(0)!);
  return fetch(
    `/api/admin/tenants/${importRequest.tenant}/_import?version=1&conflict=${
      importRequest.conflictStrategy
    }&deduceProject=${importRequest.deduceProject}&timezone=${
      importRequest.zone
    }${
      importRequest.project
        ? `&project=${importRequest.project}&create=${importRequest.newProject}`
        : ""
    }${
      importRequest.deduceProject
        ? `&projectPartSize=${importRequest.projectPartSize}`
        : ""
    }&inlineScript=${importRequest.inlineScript}`,
    {
      method: "POST",
      body: data,
    }
  );
}

interface ImportResult {
  features: number;
  keys: number;
  scripts: number;
  users: number;
  incompatibleScripts: string[];
}

export function pollForImportResult(
  tenant: string,
  id: string
): Promise<ImportResult> {
  return new Promise((resolve, reject) => {
    const interval = setInterval(() => {
      fetch(`/api/admin/tenants/${tenant}/_import/${id}`).then((response) => {
        if (response.status >= 400) {
          clearInterval(interval);
          reject(["Failed to fetch import status"]);
        } else {
          response.json().then((body) => {
            if (body.status.toUpperCase() === "FAILED") {
              clearInterval(interval);
              reject(body.errors);
            } else if (body.status.toUpperCase() === "SUCCESS") {
              clearInterval(interval);
              resolve(body);
            }
          });
        }
      });
    }, 1000);
  });
}

export function importUsersFile(tenant: string, file: FileList): Promise<any> {
  const data = new FormData();
  data.append("file", file.item(0)!);
  return fetch(`/api/admin/tenants/${tenant}/users/_import`, {
    method: "POST",
    body: data,
  });
}

export function searchEntities(
  query: string,
  filter: string[] = []
): Promise<SearchResult[]> {
  let filterString = "";
  if (filter.length > 0) {
    filterString = filter.map(encodeURIComponent).join("&filter=");
  }
  return handleFetchJsonResponse(
    fetch(
      `/api/admin/search?query=${encodeURIComponent(query)}${
        filterString ? `&filter=${filterString}` : ""
      }`
    )
  );
}

export function searchEntitiesByTenant(
  tenant: string,
  query: string,
  filter: string[] = []
): Promise<SearchResult[]> {
  let filterString = "";
  if (filter.length > 0) {
    filterString = filter.map(encodeURIComponent).join("&filter=");
  }
  return handleFetchJsonResponse(
    fetch(
      `/api/admin/tenants/${tenant}/search?query=${encodeURIComponent(query)}${
        filterString ? `&filter=${filterString}` : ""
      }`
    )
  );
}

export function fetchOpenIdConnectConfiguration(
  url: string
): Promise<Partial<OIDCSettings>> {
  return handleFetchJsonResponse(
    fetch("/api/admin/openid-connect/configuration", {
      method: "POST",
      body: JSON.stringify({ url }),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function fetchFeature(
  tenant: string,
  id: string
): Promise<TLightFeature | undefined> {
  return fetch(`/api/admin/tenants/${tenant}/features/${id}`, {
    method: "GET",
  }).then((resp) => {
    if (resp.status < 400) {
      return resp.json();
    } else {
      return Promise.resolve(undefined);
    }
  });
}

export function changeProtectionStatusForLocalContext(
  tenant: string,
  project: string,
  path: string,
  isProtected: boolean
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/projects/${project}/contexts${path}`, {
      method: "PUT",
      body: JSON.stringify({ protected: isProtected }),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}

export function changeProtectionStatusForGlobalContext(
  tenant: string,
  path: string,
  isProtected: boolean
): Promise<undefined> {
  return handleFetchWithoutResponse(
    fetch(`/api/admin/tenants/${tenant}/contexts${path}`, {
      method: "PUT",
      body: JSON.stringify({ protected: isProtected }),
      headers: {
        "Content-Type": "application/json",
      },
    })
  );
}
