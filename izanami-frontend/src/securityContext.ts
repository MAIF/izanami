import React, { JSX, useContext } from "react";
import { TLevel, TRights, TUser } from "./utils/types";

export const MODE_KEY = "izanami-dark-light-mode";
export const Modes = { light: "light", dark: "dark" } as const;
type ModeKeys = keyof typeof Modes;
export type ModeValue = typeof Modes[ModeKeys];

export interface TIzanamiContext {
  updateLightMode: (mode: ModeValue) => void;
  mode: ModeValue;
  version?: string;
  expositionUrl?: string;
  user?: TUser;
  setUser: (user: TUser) => void;
  setExpositionUrl: (url: string) => void;
  logout: () => void;
  displayModal: (content: React.FC<{ close: () => void }>) => Promise<void>;
  askConfirmation: (
    msg: JSX.Element | JSX.Element[] | string,
    onSubmit?: () => Promise<any>,
    onCancel?: () => Promise<any>,
    closeButtonText?: string,
    confirmButtonText?: string
  ) => Promise<void>;
  askPasswordConfirmation: (
    message: JSX.Element | JSX.Element[] | string,
    onConfirm: (password: string) => Promise<void>,
    title?: string
  ) => Promise<void>;
  refreshUser: () => void;
  integrations?: {
    wasmo: boolean;
    oidc: boolean;
  };
}

export const IzanamiContext = React.createContext<TIzanamiContext>({
  updateLightMode: () => {
    /* */
  },
  mode: Modes.dark,
  user: undefined,
  setUser: () => {
    /**/
  },
  logout: () => {
    /**/
  },
  askConfirmation: () => {
    return Promise.resolve();
  },
  displayModal: () => Promise.resolve(),
  askPasswordConfirmation: () => {
    return Promise.resolve();
  },
  refreshUser: () => {
    /* */
  },
  expositionUrl: undefined,
  setExpositionUrl: () => {
    /* */
  },
  integrations: undefined,
});

export function useAdmin() {
  const { user } = useContext(IzanamiContext);
  return user ? user.admin : false;
}

export function useTenantRight(tenant: string | undefined, level: TLevel) {
  const { user } = useContext(IzanamiContext);
  if (!user || !tenant) {
    return false;
  }
  if (user.admin) {
    return true;
  }

  const tenantRight = findTenantRight(user.rights, tenant);
  return tenantRight && isRightAbove(tenantRight, level);
}

export function useKeyRight(tenant: string, key: string, level: TLevel) {
  const { user } = useContext(IzanamiContext);
  const tenantAdmin = useTenantRight(tenant, TLevel.Admin);

  if (!user) {
    return false;
  }
  if (!tenant) {
    return false;
  }

  if (tenantAdmin || user.admin) {
    return true;
  }

  const currentRight = findKeyRight(user.rights, tenant, key);
  if (!currentRight) {
    return false;
  }

  return isRightAbove(currentRight, level);
}

export function useProjectRight(
  tenant: string | undefined,
  project: string | undefined,
  level: TLevel
) {
  const { user } = useContext(IzanamiContext);
  const tenantAdmin = useTenantRight(tenant, TLevel.Admin);

  if (!user) {
    return false;
  }

  if (!tenant) {
    return false;
  }

  if (tenantAdmin || user.admin) {
    return true;
  }

  const currentRight = findProjectRight(user.rights, tenant, project);
  if (!currentRight) {
    return false;
  }

  return isRightAbove(currentRight, level);
}

export function hasRightForProject(
  user: TUser,
  level: TLevel,
  project: string,
  tenant: string
): boolean {
  if (user.admin) {
    return true;
  }
  const tenantRight = user?.rights?.tenants?.[tenant];
  if (!tenantRight) {
    return false;
  }

  return (
    tenantRight.level === TLevel.Admin ||
    isRightAbove(tenantRight?.projects?.[project]?.level, level)
  );
}

export function hasRightForTenant(user: TUser, tenant: string, level: TLevel) {
  if (user.admin) {
    return true;
  }
  const tenantRight = findTenantRight(user.rights, tenant);
  return tenantRight && isRightAbove(tenantRight, level);
}

export function hasRightForKey(
  user: TUser,
  level: TLevel,
  key: string,
  tenant: string
): boolean {
  if (user.admin) {
    return true;
  }
  const tenantRight = user?.rights?.tenants?.[tenant];
  if (!tenantRight) {
    return false;
  }

  return (
    tenantRight.level === TLevel.Admin ||
    isRightAbove(tenantRight?.keys?.[key]?.level, level)
  );
}

export function hasRightForWebhook(
  user: TUser,
  level: TLevel,
  webhook: string,
  tenant: string
): boolean {
  if (user.admin) {
    return true;
  }
  const tenantRight = user?.rights?.tenants?.[tenant];
  if (!tenantRight) {
    return false;
  }

  return (
    tenantRight.level === TLevel.Admin ||
    isRightAbove(tenantRight?.webhooks?.[webhook]?.level, level)
  );
}

export function findProjectRight(
  rights: TRights,
  tenant: string,
  project?: string
): TLevel | undefined {
  if (!project) {
    return undefined;
  }
  return rights.tenants?.[tenant]?.projects?.[project]?.level;
}

export function findKeyRight(
  rights: TRights,
  tenant: string,
  key: string
): TLevel | undefined {
  return rights.tenants?.[tenant]?.keys?.[key].level;
}

export function findTenantRight(
  rights: TRights,
  tenant: string
): TLevel | undefined {
  return rights?.tenants?.[tenant]?.level;
}

function isRightAbove(currentRight: TLevel, seekedRight: TLevel) {
  if (!currentRight) {
    return false;
  }
  switch (seekedRight) {
    case TLevel.Read:
      return (
        currentRight === TLevel.Read ||
        currentRight === TLevel.Write ||
        currentRight === TLevel.Admin
      );
    case TLevel.Write:
      return currentRight === TLevel.Write || currentRight === TLevel.Admin;
    case TLevel.Admin:
      return currentRight === TLevel.Admin;
    default:
      return false;
  }
}

export function rightsBelow(currentRight?: TLevel): TLevel[] {
  if (!currentRight) {
    return [];
  }
  const result = [TLevel.Read, TLevel.Write, TLevel.Admin].filter((level) =>
    isRightAbove(currentRight, level)
  );

  return result;
}
