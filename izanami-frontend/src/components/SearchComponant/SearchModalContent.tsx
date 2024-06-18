import React, { useState, useEffect } from "react";
import { useDebounce } from "./useDebounce";
import { Link } from "react-router-dom";
import { groupBy } from "lodash";
import {
  searchEntitiesByTenant,
  searchQueryEntities,
} from "../../utils/queries";
import { useQuery } from "react-query";
import { searchQueryByTenant, searchEntities } from "../../utils/queries";

interface ISearchProps {
  tenant: string | undefined;
  user: string;
  onClose: () => void;
}

export function SearchModalContent(props: ISearchProps) {
  const { tenant, user, onClose } = props;
  const [searchQuery, setSearchQuery] = useState("");
  const [showDocuments, setShowDocuments] = useState(false);
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

  const groupedItems = groupBy(SearchItems, (item) => item.origin_table);
  return (
    <>
      <div className="search-container">
        <i className="fas fa-search search-icon" aria-hidden />
        <input
          type="text"
          id="search-input"
          name="search-form"
          title="Search in tenants"
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
              <ul className="search-ul nav flex-column">
                {Object.keys(groupedItems).map((originTable, index) => (
                  <>
                    <li className="search-ul-item" key={index}>
                      <span>
                        {originTable.charAt(0).toUpperCase() +
                          originTable.slice(1)}
                      </span>

                      {groupedItems[originTable].map((item: any) => (
                        <>
                          <ol className="search-ul nav flex-column">
                            <li
                              className="search-ul-item"
                              key={item.id}
                              aria-label={`View details for ${item.name} in ${item.origin_tenant}`}
                              onClick={() => onClose()}
                            >
                              <Link
                                to={`/tenants/${item.origin_tenant}/${item.origin_table}/${item.name}`}
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
                              </Link>
                            </li>
                          </ol>
                        </>
                      ))}
                    </li>
                  </>
                ))}
              </ul>
            ) : (
              <div style={{ margin: "10px" }}>No results found</div>
            ))}
        </div>
      )}
    </>
  );
}
