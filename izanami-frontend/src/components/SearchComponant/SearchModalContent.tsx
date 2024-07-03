import React, { useState } from "react";
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
  onClose: () => void;
}

interface SearchResult {
  id: string;
  name: string;
  origin_table: string;
  origin_tenant: string;
  project?: string;
  description: string;
}

const iconMapping = new Map<string, string>([
  ["features", "fa-rocket"],
  ["projects", "fa-building"],
  ["apikeys", "fa-key"],
  ["tags", "fa-tag"],
  ["tenants", "fa-cloud"],
  ["users", "fa-user"],
  ["webhooks", "fa-plug"],
]);
const getLinkPath = (item: SearchResult) => {
  switch (item.origin_table) {
    case "features":
      return `/tenants/${item.origin_tenant}/projects/${item.project}`;
    case "apikeys":
      return `/tenants/${item.origin_tenant}/keys`;
    case "webhooks":
      return `/tenants/${item.origin_tenant}/webhooks`;
    default:
      return `/tenants/${item.origin_tenant}/${item.origin_table}/${item.name}`;
  }
};
export function SearchModalContent({ tenant, onClose }: ISearchProps) {
  const [selectedTenant, setSelectedTenant] = useState<string | null>(tenant!);
  const [searchQuery, setSearchQuery] = useState<string>("");
  const [showDocuments, setShowDocuments] = useState<boolean>(false);
  const navigate = useNavigate();

  const useSearchEntitiesByTenant = (query: string, selectedTenant: string) => {
    const enabled = !!query;
    if (selectedTenant && selectedTenant !== "all") {
      return useQuery(
        searchQueryByTenant(selectedTenant, query),
        () => searchEntitiesByTenant(selectedTenant, query),
        { enabled }
      );
    }
    return useQuery(searchQueryEntities(query), () => searchEntities(query), {
      enabled,
    });
  };

  const {
    data: SearchItems,
    isLoading,
    isSuccess,
    isError,
  } = useSearchEntitiesByTenant(
    useDebounce(searchQuery, 500).trim(),
    selectedTenant!
  );

  const groupedItems = groupBy(
    SearchItems,
    (item: SearchResult) => item.origin_table
  );

  const handleItemClick = (item: SearchResult) => {
    const linkPath = getLinkPath(item);
    navigate(
      { pathname: linkPath },
      { state: { name: item.origin_table !== "projects" ? item.name : null } }
    );
    onClose();
  };
  const handleClearSearch = () => {
    setSearchQuery("");
    setShowDocuments(false);
  };
  return (
    <>
      {tenant && (
        <div
          className="d-flex flex-row align-items-start my-1"
          role="group"
          aria-label="Tenant selection"
        >
          <button
            onClick={() => setSelectedTenant("all")}
            className={`btn ${
              selectedTenant === "all" ? "btn-primary" : "btn-secondary"
            }`}
            value="all"
            aria-pressed={selectedTenant === "all"}
          >
            All tenants
          </button>

          <button
            onClick={() => setSelectedTenant(tenant)}
            className={`btn ${
              selectedTenant === tenant ? "btn-primary" : "btn-secondary"
            }`}
            value={tenant}
            aria-pressed={selectedTenant === tenant}
          >
            <span className="fas fa-cloud" aria-hidden></span>
            <span> {tenant}</span>
          </button>
        </div>
      )}
      <div className="search-container">
        <div className="search-container-input">
          <i className="fas fa-search search-icon" aria-hidden="true" />
          <input
            type="text"
            id="search-input"
            name="search-form"
            title="Search in tenants"
            value={searchQuery}
            onChange={(event) => {
              setSearchQuery(event.target.value);
              setShowDocuments(true);
            }}
            placeholder={`Search in ${
              tenant ? `this tenant: ${selectedTenant}` : "all tenants"
            }`}
            aria-label={`Search in ${
              tenant ? `this tenant: ${selectedTenant}` : "all tenants"
            }`}
            className="form-control"
            style={{ padding: ".375rem 1.85rem" }}
            autoFocus
          />
          {searchQuery && (
            <button
              type="button"
              onClick={handleClearSearch}
              aria-label="Clear search"
              className="clear-search-btn"
            >
              <i className="fa-regular fa-circle-xmark" />
            </button>
          )}
        </div>
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
                              {item.description
                                ? `${item.name} / description : ${item.description}`
                                : item.name}
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
    </>
  );
}
