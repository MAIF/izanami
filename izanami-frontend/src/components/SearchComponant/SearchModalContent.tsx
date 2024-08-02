import React, { ReactElement, useState } from "react";
import { useDebounce } from "./useDebounce";
import { Link, useNavigate } from "react-router-dom";
import { useQuery } from "react-query";
import {
  searchEntitiesByTenant,
  searchQueryEntities,
  searchQueryByTenant,
  searchEntities,
} from "../../utils/queries";
import { GlobalContextIcon } from "../../utils/icons";
import { SearchResultV2 } from "../../utils/types";

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

const typeDisplayInformation = new Map<
  string,
  {
    icon: () => ReactElement<any, any>;
    displayName: string;
  }
>([
  [
    "tenant",
    {
      icon: () => <i className="fas fa-cloud me-2" aria-hidden="true" />,
      displayName: "Tenants",
    },
  ],
  [
    "feature",
    {
      icon: () => <i className="fas fa-rocket me-2" aria-hidden="true" />,
      displayName: "Features",
    },
  ],
  [
    "project",
    {
      icon: () => <i className="fas fa-building me-2" aria-hidden="true" />,
      displayName: "Projects",
    },
  ],
  [
    "key",
    {
      icon: () => <i className="fas fa-key me-2" aria-hidden="true" />,
      displayName: "Keys",
    },
  ],
  [
    "tag",
    {
      icon: () => <i className="fas fa-tag me-2" aria-hidden="true" />,
      displayName: "Tags",
    },
  ],
  [
    "script",
    {
      icon: () => <i className="fas fa-code me-2" aria-hidden="true" />,
      displayName: "WASM scripts",
    },
  ],
  [
    "global_context",
    {
      icon: () => (
        <span aria-hidden="true" className="me-2">
          <GlobalContextIcon />
        </span>
      ),
      displayName: "Global contexts",
    },
  ],
  [
    "local_context",
    {
      icon: () => <i className="fas fa-filter me-2" aria-hidden="true" />,
      displayName: "Local contexts",
    },
  ],
  [
    "webhook",
    {
      icon: () => <i className="fas fa-plug me-2" aria-hidden="true" />,
      displayName: "Webhooks",
    },
  ],
]);

/*

        pathname: linkPath,
        ...(filterValue ? { search: `filter=${filterValue}` } : {}),
      }*/
const getLinkPath = (item: SearchResultV2) => {
  const { tenant } = item;
  switch (item.type) {
    case "feature": {
      const projectName = item.path.find((p) => p.type === "project")?.name;
      return `/tenants/${tenant}/projects/${projectName}?filter=${item.name}`;
    }
    case "project":
      return `/tenants/${tenant}/projects/${item.name}`;
    case "key":
      return `/tenants/${tenant}/keys?filter=${item.name}`;
    case "tag":
      return `/tenants/${tenant}/tags/${item.name}`;
    case "script":
      return `/tenants/${tenant}/scripts?filter=${item.name}`;
    case "global_context": {
      const maybeOpen = item.path
        .filter((p) => p.type === "global_context")
        .map((p) => p.name);
      const open = [...maybeOpen, item.name].join("/");
      return `/tenants/${tenant}/contexts?open=["${open}"]`;
    }

    case "local_context": {
      const projectName = item.path.find((p) => p.type === "project")?.name;
      const maybeOpen = item.path
        .filter(
          (p) => p.type === "global_context" || p.type === "local_context"
        )
        .map((p) => p.name);
      const open = [...maybeOpen, item.name].join("/");

      return `/tenants/${tenant}/projects/${projectName}/contexts?open=["${open}"]`;
    }
    case "webhook":
      return `/tenants/${tenant}/webhooks`;
  }
};

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

export function SearchModalContent({ tenant, onClose }: ISearchProps) {
  const [selectedTenant, setSelectedTenant] = useState<string | null>(tenant!);
  const [searchQuery, setSearchQuery] = useState<string>("");
  const [showDocuments, setShowDocuments] = useState<boolean>(false);
  const navigate = useNavigate();

  const {
    data: searchItems,
    isLoading,
    isSuccess,
    isError,
  } = useSearchEntitiesByTenant(
    useDebounce(searchQuery, 500).trim(),
    selectedTenant!
  );

  const groupedItems =
    searchItems?.reduce((acc, next) => {
      const type = next.type;
      if (!acc.has(type)) {
        acc.set(type, []);
      }
      acc.get(type)?.push(next);
      return acc;
    }, new Map<string, SearchResultV2[]>()) ??
    new Map<string, SearchResultV2[]>();

  console.log("groupedItems", groupedItems);

  const handleItemClick = (item: SearchResultV2) => {
    const navigationTarget = getLinkPath(item);
    navigate(navigationTarget);

    /*if (item.origin_table === "Contexts") {
      const contextPath = item.id.split("_").slice(1).join("/");
      navigate({
        pathname: linkPath,
        search: `open=["${contextPath}"]`,
      });
    } else {
      let filterValue = null;
      if (item.origin_table === "Global") {
        filterValue = item.id
          .slice(item.id.indexOf("_") + 1)
          .split("_")
          .join("/");
      } else {
        filterValue = item.origin_table !== "Projects" ? item.name : null;
      }
      navigate({
        pathname: linkPath,
        ...(filterValue ? { search: `filter=${filterValue}` } : {}),
      });
    }*/

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
            className={`btn btn-secondary${
              selectedTenant === "all" ? " search-form-button-selected" : ""
            }`}
            style={{ backgroundColor: "var(--color-blue) !important" }}
            value="all"
            aria-pressed={selectedTenant === "all"}
          >
            All tenants
          </button>

          <button
            onClick={() => setSelectedTenant(tenant)}
            className={`btn btn-secondary${
              selectedTenant === tenant ? " search-form-button-selected" : ""
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
              (groupedItems?.size > 0 ? (
                <ul className="search-ul nav flex-column">
                  {[...groupedItems.keys()].map((originTable) => (
                    <li className="search-ul-item" key={originTable}>
                      <span>
                        {typeDisplayInformation.get(originTable)?.displayName ??
                          ""}
                      </span>
                      {groupedItems.get(originTable)?.map((item) => (
                        <ol
                          className="search-ul nav flex-column"
                          key={item.name}
                        >
                          <li
                            className="search-ul-item"
                            onClick={() => {
                              //handleItemClick(item)}
                            }}
                          >
                            <Link
                              to={getLinkPath(item)}
                              onClick={() => onClose()}
                            >
                              <ul
                                className="breadcrumb"
                                style={{ marginBottom: "0rem" }}
                              >
                                {item.path.map((pathElement, index) => (
                                  <li
                                    className="breadcrumb-item"
                                    key={`${item.name}-${index}`}
                                  >
                                    {typeDisplayInformation
                                      .get(pathElement.type)
                                      ?.icon() ?? ""}
                                    {pathElement.name}
                                  </li>
                                ))}
                                <li
                                  className="breadcrumb-item"
                                  key={`${item.name}-name`}
                                >
                                  {typeDisplayInformation
                                    .get(item.type)
                                    ?.icon() ?? ""}
                                  {item.name}
                                </li>
                                {/*
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
                                </li>*/}
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
