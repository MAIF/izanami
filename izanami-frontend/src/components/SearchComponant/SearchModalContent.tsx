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
import { GlobalContextIcon } from "../../utils/icons";

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
  parent: string;
}

const iconMapping = new Map<string, string>([
  ["Features", "fa-rocket"],
  ["Projects", "fa-building"],
  ["Apikeys", "fa-key"],
  ["Tags", "fa-tag"],
  ["Tenants", "fa-cloud"],
  ["Users", "fa-user"],
  ["Webhooks", "fa-plug"],
  ["Contexts", "fa-filter"],
  ["Wasm Scripts", "fa-code"],
]);
const getLinkPath = (item: SearchResult) => {
  switch (item.origin_table) {
    case "Features":
      return `/tenants/${item.origin_tenant}/projects/${item.project}`;
    case "Apikeys":
      return `/tenants/${item.origin_tenant}/keys`;
    case "Webhooks":
      return `/tenants/${item.origin_tenant}/webhooks`;
    case "Contexts":
      return `/tenants/${item.origin_tenant}/${
        item.description === "Projects" ? `projects/${item.project}/` : ""
      }contexts`;
    case "Wasm Scripts":
      return `/tenants/${item.origin_tenant}/scripts`;
    default:
      return `/tenants/${
        item.origin_tenant
      }/${item.origin_table.toLowerCase()}/${item.name}`;
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
    if (item.origin_table === "Global") {
      navigate(
        { pathname: linkPath },
        {
          state: {
            name: item.id
              .slice(item.id.indexOf("_") + 1)
              .split("_")
              .join("/"),
          },
        }
      );
    } else {
      navigate(
        { pathname: linkPath },
        { state: { name: item.origin_table !== "Projects" ? item.name : null } }
      );
    }
    onClose();
  };
  const handleClearSearch = () => {
    setSearchQuery("");
    setShowDocuments(false);
  };
  return (
    <>
      {tenant && tenant !== "all" && (
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
              selectedTenant === "all"
                ? "all tenants"
                : `this tenant: ${selectedTenant}`
            }`}
            aria-label={`Search in ${
              selectedTenant === "all"
                ? "all tenants"
                : `this tenant: ${selectedTenant}`
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
                      <span>{originTable}</span>
                      {groupedItems[originTable].map((item) => (
                        <ol className="search-ul nav flex-column" key={item.id}>
                          <li
                            className="search-ul-item"
                            aria-label={`View details for ${item.name} in ${item.origin_tenant}`}
                            onClick={() => handleItemClick(item)}
                          >
                            <Link to={getLinkPath(item)}>
                              <ul
                                className="breadcrumb"
                                style={{ marginBottom: "0rem" }}
                              >
                                {selectedTenant === "all" && (
                                  <li className="breadcrumb-item">
                                    <i
                                      className="fas fa-cloud me-2"
                                      aria-hidden="true"
                                    />
                                    {item.origin_tenant}
                                  </li>
                                )}

                                {item.project &&
                                  item.origin_table !== "Projects" && (
                                    <li className="breadcrumb-item">
                                      <i
                                        className="fas fa-building me-2"
                                        aria-hidden="true"
                                      />
                                      {item.project}
                                    </li>
                                  )}
                                {item.parent && (
                                  <li className="breadcrumb-item">
                                    {item.origin_table === "Context" &&
                                    item.description === "Global" ? (
                                      <GlobalContextIcon className="me-2" />
                                    ) : (
                                      <i
                                        className="fas fa-filter me-2"
                                        aria-hidden="true"
                                      />
                                    )}
                                    {item.parent
                                      .slice(item.parent.indexOf("_") + 1)
                                      .split("_")
                                      .join(" / ")}
                                  </li>
                                )}
                                <li className="breadcrumb-item">
                                  {item.origin_table === "Contexts" &&
                                  item.description === "Global" ? (
                                    <GlobalContextIcon className="me-2" />
                                  ) : (
                                    <i
                                      className={`fas ${iconMapping.get(
                                        originTable
                                      )} me-2`}
                                      aria-hidden="true"
                                    />
                                  )}
                                  {item.description &&
                                  item.similarity_description >=
                                    item.similarity_name
                                    ? `${item.name} / description : ${item.description}`
                                    : item.name}
                                </li>
                              </ul>
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
