import React, { useState, useEffect } from "react";
import { useDebounce } from "../useDebounce";
import {
  searchEntitiesByTenant,
  searchQueryEntities,
} from "../../utils/queries";
import { useQuery } from "react-query";
import { searchQueryByTenant, searchEntities } from "../../utils/queries";

interface ISearchProps {
  tenant: string | undefined;
  user: string;
}

export function SearchDropDown(props: ISearchProps) {
  const { tenant, user } = props;
  const [searchQuery, setSearchQuery] = useState("");
  const debouncedSearchQuery = useDebounce(searchQuery, 500);
  const iconMapping = new Map([
    ["features", "fa-rocket"],
    ["projects", "fa-building"],
    ["apikeys", "fa-key"],
    ["tags", "fa-tag"],
    ["tenants", "fa-cloud"],
    ["users", "fa-user"],
  ]);

  const useSearchEntitiesByTenant = (query: string) => {
    if (tenant) {
      return useQuery(
        searchQueryByTenant(tenant, query),
        () => searchEntitiesByTenant(tenant!, query),
        {
          enabled: !!query,
        }
      );
    }
    return useQuery(
      searchQueryEntities(query),
      () => searchEntities(user, query),
      {
        enabled: !!query,
      }
    );
  };
  const {
    data: SearchItems,
    isLoading,
    isSuccess,
    isError,
  } = useSearchEntitiesByTenant(debouncedSearchQuery);
  const [showDocuments, setShowDocuments] = useState(false);

  const handleSearchInput = (event: any) => {
    setSearchQuery(event.target.value);
    setShowDocuments(false);
  };

  useEffect(() => {
    if (debouncedSearchQuery.trim() !== "") {
      setShowDocuments(true);
    } else {
      setShowDocuments(false);
    }
  }, [debouncedSearchQuery]);

  const groupedItems = SearchItems?.reduce((acc: any, item: any) => {
    if (!acc[item.origin_table]) {
      acc[item.origin_table] = [];
    }
    acc[item.origin_table].push(item);
    return acc;
  }, {});

  return (
    <>
      <div className="search-container">
        <i className="fas fa-search search-icon" />
        <input
          type="text"
          value={searchQuery}
          onChange={handleSearchInput}
          placeholder={`Search in ${
            tenant ? "this tenant: " + tenant : "all tenants"
          }`}
          className="form-control"
          style={{ padding: ".375rem 1.85rem" }}
        ></input>
      </div>

      {showDocuments && (
        <div style={{ margin: "10px" }}>
          {isLoading && <div>Loading...</div>}
          {isError && <div>There was an error for fetching data.</div>}
          {isSuccess &&
            (Object.keys(groupedItems).length > 0 ? (
              Object.keys(groupedItems).map((originTable) => (
                <>
                  <ul className="search-ul nav flex-column">
                    <li className="search-ul-item" key={originTable}>
                      <span>
                        {originTable.charAt(0).toUpperCase() +
                          originTable.slice(1)}
                      </span>

                      {groupedItems[originTable].map((item: any) => (
                        <>
                          <ol className="search-ul nav flex-column">
                            <li className="search-ul-item" key={item.id}>
                              <a
                                href={`/tenants/${item.origin_tenant}/${item.origin_table}/${item.name}`}
                              >
                                <i className="fas fa-cloud me-2" aria-hidden />
                                {item.origin_tenant} /{" "}
                                <i
                                  className={`fas ${iconMapping.get(
                                    originTable
                                  )} me-2`}
                                  aria-hidden
                                />
                                {item.name}
                              </a>
                            </li>
                          </ol>
                        </>
                      ))}
                    </li>
                  </ul>
                </>
              ))
            ) : (
              <div>No results found</div>
            ))}
        </div>
      )}
    </>
  );
}
