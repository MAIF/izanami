import {
  TLevel,
  TokenTenantRights,
  TokenTenantRightsArray,
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

function projectOrKeyArrayToObject(arr: { name: string; level?: TLevel }[]) {
  return arr.reduce((acc, { name, level }) => {
    acc[name] = { level };
    return acc;
  }, {} as { [x: string]: any });
}

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
