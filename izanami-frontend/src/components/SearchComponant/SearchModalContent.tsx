import React, { useState, useEffect, ChangeEvent } from "react";
import { useDebounce } from "./useDebounce";
import { Link, useNavigate } from "react-router-dom";
import { groupBy } from "lodash";
import { useQuery } from "react-query";
import {
  searchEntitiesByTenant,
  searchQueryEntities,
  searchQueryByTenant,
  searchEntities,
} from "../../utils/queries";

interface ISearchProps {
  tenant?: string;
  user: string;
  onClose: () => void;
}

interface SearchResult {
  id: string;
  name: string;
  origin_table: string;
  origin_tenant: string;
  project?: string;
}

const iconMapping = new Map<string, string>([
  ["features", "fa-rocket"],
  ["projects", "fa-building"],
  ["apikeys", "fa-key"],
  ["tags", "fa-tag"],
  ["tenants", "fa-cloud"],
  ["users", "fa-user"],
]);

export function SearchModalContent({ tenant, user, onClose }: ISearchProps) {
  const [searchQuery, setSearchQuery] = useState<string>("");
  const [showDocuments, setShowDocuments] = useState<boolean>(false);
  const debouncedSearchQuery = useDebounce(searchQuery, 500);
  const navigate = useNavigate();

  const useSearchEntitiesByTenant = (query: string) => {
    const enabled = !!query;
    if (tenant) {
      return useQuery(
        searchQueryByTenant(tenant, query),
        () => searchEntitiesByTenant(tenant, query),
        { enabled }
      );
    }
    return useQuery(
      searchQueryEntities(query),
      () => searchEntities(user, query),
      { enabled }
    );
  };

  const {
    data: SearchItems,
    isLoading,
    isSuccess,
    isError,
  } = useSearchEntitiesByTenant(debouncedSearchQuery);

  const handleSearchInput = (event: ChangeEvent<HTMLInputElement>) => {
    setSearchQuery(event.target.value);
    setShowDocuments(false);
  };

  useEffect(() => {
    setShowDocuments(debouncedSearchQuery.trim() !== "");
  }, [debouncedSearchQuery]);

  const groupedItems = groupBy(
    SearchItems,
    (item: SearchResult) => item.origin_table
  );

  const getLinkPath = (item: SearchResult) => {
    switch (item.origin_table) {
      case "features":
        return `/tenants/${item.origin_tenant}/projects/${item.project}`;
      case "apikeys":
        return `/tenants/${item.origin_tenant}/keys`;
      default:
        return `/tenants/${item.origin_tenant}/${item.origin_table}/${item.name}`;
    }
  };
  const handleItemClick = (item: SearchResult) => {
    const linkPath = getLinkPath(item);
    navigate(linkPath, { state: { item } });
    onClose();
  };

  return (
    <div className="search-container">
      <div className="d-flex flex-row align-items-start my-1">
        <button className="btn btn-secondary">All tenants </button>
        <button className="btn btn-secondary">{tenant} </button>
      </div>

      <i className="fas fa-search search-icon" aria-hidden="true" />
      <input
        type="text"
        id="search-input"
        name="search-form"
        title="Search in tenants"
        value={searchQuery}
        onChange={handleSearchInput}
        placeholder={`Search in ${
          tenant ? `this tenant: ${tenant}` : "all tenants"
        }`}
        className="form-control"
        style={{ padding: ".375rem 1.85rem" }}
      />
      {showDocuments && (
        <div className="search-result">
          {isLoading && <div>Search something...</div>}
          {isError && <div>There was an error fetching data.</div>}
          {isSuccess &&
            (Object.keys(groupedItems).length > 0 ? (
              <ul className="search-ul nav flex-column">
                {Object.keys(groupedItems).map((originTable) => (
                  <li className="search-ul-item" key={originTable}>
                    <span>
                      {originTable.charAt(0).toUpperCase() +
                        originTable.slice(1)}
                    </span>
                    {groupedItems[originTable].map((item: SearchResult) => (
                      <ol className="search-ul nav flex-column" key={item.id}>
                        <li
                          className="search-ul-item"
                          aria-label={`View details for ${item.name} in ${item.origin_tenant}`}
                          onClick={() => handleItemClick(item)}
                        >
                          <Link to={getLinkPath(item)}>
                            <i
                              className="fas fa-cloud me-2"
                              aria-hidden="true"
                            />
                            {item.origin_tenant} /{" "}
                            <i
                              className={`fas ${iconMapping.get(
                                originTable
                              )} me-2`}
                              aria-hidden="true"
                            />
                            {item.name}
                          </Link>
                        </li>
                      </ol>
                    ))}
                  </li>
                ))}
              </ul>
            ) : (
              <div className="search-result">No results found</div>
            ))}
        </div>
      )}
    </div>
  );
}
