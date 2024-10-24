import React, { ReactElement } from "react";
import { SearchResult } from "./types";
import { GlobalContextIcon } from "./icons";

export interface Option {
  value: string;
  label: string;
}
export interface ISearchProps {
  tenant?: string;
  allTenants?: string[];
  onClose: () => void;
}
export const SEARCH_TYPES_LABEL = {
  TENANT: "Tenants",
  PROJECT: "Projects",
  FEATURE: "Features",
  KEY: "Keys",
  GLOBAL_CONTEXT: "Global Contexts",
  LOCAL_CONTEXT: "Local Contexts",
  TAG: "Tags",
  WEBHOOK: "WebHooks",
  SCRIPT: "WASM scripts",
};
export const SEARCH_TYPES_VALUE = {
  TENANT: "tenant",
  PROJECT: "project",
  FEATURE: "feature",
  KEY: "key",
  GLOBAL_CONTEXT: "global_context",
  LOCAL_CONTEXT: "local_context",
  TAG: "tag",
  WEBHOOK: "webhook",
  SCRIPT: "script",
};

export type SearchModalStatus = { all: true } | { all: false; tenant: string };
export type SearchResultStatus =
  | { state: "SUCCESS"; results: SearchResult[] }
  | { state: "ERROR"; error: string }
  | { state: "PENDING" }
  | { state: "INITIAL" };

export const getLinkPath = (item: SearchResult) => {
  const { tenant } = item;
  switch (item.type) {
    case SEARCH_TYPES_VALUE.FEATURE: {
      const projectName = item.path.find(
        (p) => p.type === SEARCH_TYPES_VALUE.PROJECT
      )?.name;
      return `/tenants/${tenant}/projects/${projectName}?filter=${item.name}`;
    }
    case SEARCH_TYPES_VALUE.PROJECT:
      return `/tenants/${tenant}/projects/${item.name}`;
    case SEARCH_TYPES_VALUE.KEY:
      return `/tenants/${tenant}/keys?filter=${item.name}`;
    case SEARCH_TYPES_VALUE.TAG:
      return `/tenants/${tenant}/tags/${item.name}`;
    case SEARCH_TYPES_VALUE.SCRIPT:
      return `/tenants/${tenant}/scripts?filter=${item.name}`;
    case SEARCH_TYPES_VALUE.GLOBAL_CONTEXT: {
      const maybeOpen = item.path
        .filter((p) => p.type === SEARCH_TYPES_VALUE.GLOBAL_CONTEXT)
        .map((p) => p.name);
      const open = [...maybeOpen, item.name].join("/");
      return `/tenants/${tenant}/contexts?open=["${open}"]`;
    }
    case SEARCH_TYPES_VALUE.LOCAL_CONTEXT: {
      const projectName = item.path.find(
        (p) => p.type === SEARCH_TYPES_VALUE.PROJECT
      )?.name;
      const maybeOpen = item.path
        .filter(
          (p) =>
            p.type === SEARCH_TYPES_VALUE.GLOBAL_CONTEXT ||
            p.type === SEARCH_TYPES_VALUE.LOCAL_CONTEXT
        )
        .map((p) => p.name);
      const open = [...maybeOpen, item.name].join("/");

      return `/tenants/${tenant}/projects/${projectName}/contexts?open=["${open}"]`;
    }
    case SEARCH_TYPES_VALUE.WEBHOOK:
      return `/tenants/${tenant}/webhooks`;
  }
};

export const typeDisplayInformation = new Map<
  string,
  {
    icon: () => ReactElement<any, any>;
    displayName: string;
  }
>([
  [
    SEARCH_TYPES_VALUE.TENANT,
    {
      icon: () => <i className="fas fa-cloud me-2" aria-hidden="true" />,
      displayName: SEARCH_TYPES_LABEL.TENANT,
    },
  ],
  [
    SEARCH_TYPES_VALUE.FEATURE,
    {
      icon: () => <i className="fas fa-rocket me-2" aria-hidden="true" />,
      displayName: SEARCH_TYPES_LABEL.FEATURE,
    },
  ],
  [
    SEARCH_TYPES_VALUE.PROJECT,
    {
      icon: () => <i className="fas fa-building me-2" aria-hidden="true" />,
      displayName: SEARCH_TYPES_LABEL.PROJECT,
    },
  ],
  [
    SEARCH_TYPES_VALUE.KEY,
    {
      icon: () => <i className="fas fa-key me-2" aria-hidden="true" />,
      displayName: SEARCH_TYPES_LABEL.KEY,
    },
  ],
  [
    SEARCH_TYPES_VALUE.TAG,
    {
      icon: () => <i className="fas fa-tag me-2" aria-hidden="true" />,
      displayName: SEARCH_TYPES_LABEL.TAG,
    },
  ],
  [
    SEARCH_TYPES_VALUE.SCRIPT,
    {
      icon: () => <i className="fas fa-code me-2" aria-hidden="true" />,
      displayName: SEARCH_TYPES_LABEL.SCRIPT,
    },
  ],
  [
    SEARCH_TYPES_VALUE.GLOBAL_CONTEXT,
    {
      icon: () => (
        <span aria-hidden="true" className="me-2">
          <GlobalContextIcon />
        </span>
      ),
      displayName: SEARCH_TYPES_LABEL.GLOBAL_CONTEXT,
    },
  ],
  [
    SEARCH_TYPES_VALUE.LOCAL_CONTEXT,
    {
      icon: () => <i className="fas fa-filter me-2" aria-hidden="true" />,
      displayName: SEARCH_TYPES_LABEL.LOCAL_CONTEXT,
    },
  ],
  [
    SEARCH_TYPES_VALUE.WEBHOOK,
    {
      icon: () => <i className="fas fa-plug me-2" aria-hidden="true" />,
      displayName: SEARCH_TYPES_LABEL.WEBHOOK,
    },
  ],
]);

export const FilterOptions = [
  { value: SEARCH_TYPES_VALUE.PROJECT, label: SEARCH_TYPES_LABEL.PROJECT },
  { value: SEARCH_TYPES_VALUE.FEATURE, label: SEARCH_TYPES_LABEL.FEATURE },
  { value: SEARCH_TYPES_VALUE.KEY, label: SEARCH_TYPES_LABEL.KEY },
  {
    value: SEARCH_TYPES_VALUE.GLOBAL_CONTEXT,
    label: SEARCH_TYPES_LABEL.GLOBAL_CONTEXT,
  },
  {
    value: SEARCH_TYPES_VALUE.LOCAL_CONTEXT,
    label: SEARCH_TYPES_LABEL.LOCAL_CONTEXT,
  },
  { value: SEARCH_TYPES_VALUE.TAG, label: SEARCH_TYPES_LABEL.TAG },
  { value: SEARCH_TYPES_VALUE.WEBHOOK, label: SEARCH_TYPES_LABEL.WEBHOOK },
  { value: SEARCH_TYPES_VALUE.SCRIPT, label: SEARCH_TYPES_LABEL.SCRIPT },
];
