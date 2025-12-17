import {
  TLevel,
  TLevelWithNone,
  TokenTenantRights,
  TokenTenantRightsArray,
  TProjectLevel,
  TProjectLevelWithNone,
  TRights,
} from "./types";

export function tokenRightsToObject(
  rights: TokenTenantRightsArray
): TokenTenantRights {
  return rights.reduce((acc, [tenant, rights]) => {
    if (tenant && rights && rights.length > 0) {
      acc[tenant] = rights;
    }
    return acc;
  }, {} as TokenTenantRights);
}

export function rightStateArrayToBackendMap(state?: State): TRights {
  if (!state) {
    return { tenants: {} };
  }
  const backendRights = state.reduce(
    (
      acc,
      {
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
      }
    ) => {
      acc[name] = {
        level,
        projects: projectOrKeyArrayToObject(projects),
        keys: projectOrKeyArrayToObject(keys),
        webhooks: projectOrKeyArrayToObject(webhooks),
        defaultProjectRight: defaultProjectRight,
        defaultKeyRight: defaultKeyRight,
        defaultWebhookRight: defaultWebhookRight,
        maxProjectRight: maxProjectRight,
        maxKeyRight: maxKeyRight,
        maxWebhookRight: maxWebhookRight,
        maxTenantRight: maxTenantRight,
      };
      return acc;
    },
    {} as { [x: string]: any }
  );

  return { tenants: backendRights };
}

function projectOrKeyArrayToObject(
  arr: { name: string; level?: TProjectLevel }[]
) {
  return arr.reduce((acc, { name, level }) => {
    acc[name] = { level };
    return acc;
  }, {} as { [x: string]: any });
}

export type State = {
  name: string;
  level: TLevelWithNone;
  projects: {
    name: string;
    level?: TProjectLevel;
  }[];
  keys: {
    name: string;
    level?: TLevel;
  }[];
  webhooks: {
    name: string;
    level?: TLevel;
  }[];
  defaultProjectRight: TProjectLevelWithNone;
  defaultKeyRight: TLevelWithNone;
  defaultWebhookRight: TLevelWithNone;
  maxProjectRight: TProjectLevelWithNone;
  maxKeyRight: TLevelWithNone;
  maxWebhookRight: TLevelWithNone;
  maxTenantRight: TLevelWithNone;
}[];
