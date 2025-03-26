import React, { useEffect, useState, useMemo } from "react";
import { searchEntitiesByTenant, searchEntities } from "../../utils/queries";
import { SearchResult } from "../../utils/types";
import SearchInput from "./SearchInput";
import { SearchResults } from "./SearchResults";
import { SearchAction } from "./SearchAction";
import {
  ISearchProps,
  SearchModalStatus,
  SearchResultStatus,
  FilterOptions,
  Option,
} from "./searchUtils";

export function SearchModalContent({
  tenant,
  allTenants,
  onClose,
}: ISearchProps) {
  const [modalStatus, setModalStatus] = useState<SearchModalStatus>(
    tenant ? { all: false, tenant } : { all: true }
  );
  const [resultStatus, setResultStatus] = useState<SearchResultStatus>({
    state: "INITIAL",
  });

  const [searchTerm, setSearchTerm] = useState("");
  const groupedItems = useMemo(() => {
    if (resultStatus.state === "SUCCESS") {
      return resultStatus.results.reduce((acc, next) => {
        const type = next.type;
        if (!acc.has(type)) {
          acc.set(type, []);
        }
        acc.get(type)?.push(next);
        return acc;
      }, new Map<string, SearchResult[]>());
    }
    return new Map<string, SearchResult[]>();
  }, [resultStatus]);
  const [filters, setFilters] = React.useState<Option[]>([]);
  const performSearch = (term: string) => {
    setResultStatus({ state: "PENDING" });
    if (modalStatus.all) {
      searchEntities(
        term,
        filters?.map((v) => v.value)
      )
        .then((r) => setResultStatus({ state: "SUCCESS", results: r }))
        .catch(() => {
          setResultStatus({
            state: "ERROR",
            error: "There was an error fetching data",
          });
        });
    } else {
      searchEntitiesByTenant(
        modalStatus.tenant,
        term,
        filters?.map((v) => v.value)
      )
        .then((r) => setResultStatus({ state: "SUCCESS", results: r }))
        .catch(() =>
          setResultStatus({
            state: "ERROR",
            error: "There was an error fetching data",
          })
        );
    }
  };

  useEffect(() => {
    if (searchTerm.length > 0) {
      const delayDebounceFn = setTimeout(() => {
        if (searchTerm) {
          performSearch(searchTerm);
        }
      }, 500);

      return () => clearTimeout(delayDebounceFn);
    }
  }, [searchTerm, filters, modalStatus]);

  return (
    <>
      <SearchAction
        tenant={tenant}
        allTenants={allTenants}
        modalStatus={modalStatus}
        setModalStatus={setModalStatus}
        filterOptions={FilterOptions}
        setFilters={setFilters}
      />
      <SearchInput
        modalStatus={modalStatus}
        setSearchTerm={setSearchTerm}
        setResultStatus={setResultStatus}
      />

      <SearchResults
        modalStatus={modalStatus}
        resultStatus={resultStatus}
        groupedItems={groupedItems}
        filterOptions={filters.map((option) => option.value)}
        onClose={onClose}
      />
    </>
  );
}
