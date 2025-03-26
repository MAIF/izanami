import { ReactElement } from "react";
import { SearchResult } from "../../utils/types";
import { SearchTypeEnum } from "./SearchTypes";

export interface Option {
  value: string;
  label: string;
}
export interface ISearchProps {
  tenant?: string;
  allTenants?: string[];
  onClose: () => void;
}

export type SearchModalStatus = { all: true } | { all: false; tenant: string };
export type SearchResultStatus =
  | { state: "SUCCESS"; results: SearchResult[] }
  | { state: "ERROR"; error: string }
  | { state: "PENDING" }
  | { state: "INITIAL" };

export const getLinkPath = (item: SearchResult) => {
  const { tenant } = item;
  switch (item.type) {
    case SearchTypeEnum.FEATURE.value: {
      const projectName = item.path.find(
        (p) => p.type === SearchTypeEnum.PROJECT.value
      )?.name;
      return `/tenants/${tenant}/projects/${projectName}?filter=${item.name}`;
    }
    case SearchTypeEnum.PROJECT.value:
      return `/tenants/${tenant}/projects/${item.name}`;
    case SearchTypeEnum.KEY.value:
      return `/tenants/${tenant}/keys?filter=${item.name}`;
    case SearchTypeEnum.TAG.value:
      return `/tenants/${tenant}/tags/${item.name}`;
    case SearchTypeEnum.SCRIPT.value:
      return `/tenants/${tenant}/scripts?filter=${item.name}`;
    case SearchTypeEnum.GLOBAL_CONTEXT.value: {
      const maybeOpen = item.path
        .filter((p) => p.type === SearchTypeEnum.GLOBAL_CONTEXT.value)
        .map((p) => p.name);
      const open = [...maybeOpen, item.name].join("/");
      return `/tenants/${tenant}/contexts?open=["${open}"]`;
    }
    case SearchTypeEnum.LOCAL_CONTEXT.value: {
      const projectName = item.path.find(
        (p) => p.type === SearchTypeEnum.PROJECT.value
      )?.name;
      const maybeOpen = item.path
        .filter(
          (p) =>
            p.type === SearchTypeEnum.GLOBAL_CONTEXT.value ||
            p.type === SearchTypeEnum.LOCAL_CONTEXT.value
        )
        .map((p) => p.name);
      const open = [...maybeOpen, item.name].join("/");

      return `/tenants/${tenant}/projects/${projectName}/contexts?open=["${open}"]`;
    }
    case SearchTypeEnum.WEBHOOK.value:
      return `/tenants/${tenant}/webhooks`;
  }
};

export const typeDisplayInformation = new Map<
  string,
  {
    icon: () => ReactElement<any, any>;
    displayName: string;
  }
>(
  Object.values(SearchTypeEnum).map((type) => [
    type.value,
    {
      icon: () => type.icon,
      displayName: type.label,
    },
  ])
);
export const FilterOptions = Object.values(SearchTypeEnum)
  .filter((type) => type.value !== "tenant")
  .map((type) => ({
    value: type.value,
    label: type.label,
    icon: type.icon,
  }));
