import React, { ReactElement, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { searchEntitiesByTenant, searchEntities } from "../../utils/queries";
import { GlobalContextIcon } from "../../utils/icons";
import { SearchResult } from "../../utils/types";
import { debounce } from "lodash";
import Select from "react-select";
import { customStyles } from "../../styles/reactSelect";
interface ISearchProps {
  tenant?: string;
  allTenants?: string[];
  onClose: () => void;
}
const SEARCH_TYPES_LABEL = {
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
const SEARCH_TYPES_VALUE = {
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
const typeDisplayInformation = new Map<
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

const getLinkPath = (item: SearchResult) => {
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

type SearchModalStatus = { all: true } | { all: false; tenant: string };
type SearchResultStatus =
  | { state: "SUCCESS"; results: SearchResult[] }
  | { state: "ERROR"; error: string }
  | { state: "PENDING" }
  | { state: "INITIAL" };

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

  const inputRef = useRef<HTMLInputElement | null>(null);
  const groupedItems =
    resultStatus.state === "SUCCESS"
      ? resultStatus.results.reduce((acc, next) => {
          const type = next.type;
          if (!acc.has(type)) {
            acc.set(type, []);
          }
          acc.get(type)?.push(next);
          return acc;
        }, new Map<string, SearchResult[]>())
      : new Map<string, SearchResult[]>();
  const clearInput = () => {
    if (inputRef.current) {
      inputRef.current.value = "";
      setResultStatus({ state: "INITIAL" });
    }
  };
  interface Option {
    value: string;
    label: string;
  }
  const [filters, setFilters] = React.useState<Option[]>([]);

  const filterOptions = [
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
  const [searchTerm, setSearchTerm] = useState("");

  useEffect(() => {
    if (searchTerm) {
      performSearch(searchTerm);
    }
  }, [modalStatus, filters]);

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

  return (
    <>
      <div
        className="d-flex flex-row align-items-start my-1 gap-1"
        role="group"
        aria-label="Tenant selection"
      >
        <div className="d-flex flex-row gap-1 mb-2 mb-md-0">
          <button
            onClick={() => setModalStatus({ all: true })}
            className={`btn btn-secondary${
              modalStatus.all ? " search-form-button-selected" : ""
            }`}
            style={{ backgroundColor: "var(--color-blue) !important" }}
            value="all"
            aria-pressed={modalStatus.all}
          >
            All tenants
          </button>
          {tenant && tenant !== "all" && (
            <>
              <button
                onClick={() => setModalStatus({ all: false, tenant })}
                className={`btn btn-secondary${
                  !modalStatus.all && modalStatus.tenant === tenant
                    ? " search-form-button-selected"
                    : ""
                }`}
                value={tenant}
                aria-pressed={!modalStatus.all && modalStatus.tenant === tenant}
              >
                <span className="fas fa-cloud" aria-hidden></span>
                <span> {tenant}</span>
              </button>
            </>
          )}
        </div>
        <div className="d-flex flex-column flex-md-row gap-2 flex-grow-1">
          {!tenant && (
            <Select
              options={allTenants?.map((te) => ({ label: te, value: te }))}
              onChange={(selectedTenant) => {
                if (selectedTenant) {
                  setModalStatus({ all: false, tenant: selectedTenant.value });
                } else {
                  setModalStatus({ all: true });
                }
              }}
              styles={customStyles}
              isClearable
              placeholder="Select Tenant"
            />
          )}

          <Select
            options={filterOptions}
            onChange={(e) => setFilters(e as Option[])}
            styles={customStyles}
            isMulti
            isClearable
            placeholder="Apply filter"
          />
        </div>
      </div>
      <div className="search-container">
        <div className="search-container-input">
          <i className="fas fa-search search-icon" aria-hidden="true" />
          <input
            ref={inputRef}
            type="text"
            id="search-input"
            name="search-form"
            title="Search in tenants"
            onChange={debounce((event) => {
              const term = event.target.value;
              setSearchTerm(term);
              if (term) {
                performSearch(term);
              } else {
                clearInput();
              }
            }, 200)}
            placeholder={`Search in ${
              modalStatus.all
                ? "all tenants"
                : `this tenant: ${modalStatus.tenant}`
            }`}
            aria-label={`Search in ${
              modalStatus.all
                ? "all tenants"
                : `this tenant: ${modalStatus.tenant}`
            }`}
            className="form-control"
            style={{ padding: ".375rem 1.85rem" }}
            autoFocus
          />
          {inputRef?.current?.value && (
            <button
              type="button"
              onClick={() => {
                clearInput();
              }}
              aria-label="Clear search"
              className="clear-search-btn"
            >
              <i className="fa-regular fa-circle-xmark" />
            </button>
          )}
        </div>
        {resultStatus.state !== "INITIAL" && (
          <div className="search-result">
            {resultStatus.state === "ERROR" ? (
              <div>{resultStatus.error}</div>
            ) : resultStatus.state === "PENDING" ? (
              <div>Searching...</div>
            ) : (
              resultStatus.state === "SUCCESS" &&
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
                          <li className="search-ul-item">
                            <Link
                              to={getLinkPath(item) || "#"}
                              onClick={() => onClose()}
                            >
                              <ul
                                className="breadcrumb"
                                style={{
                                  marginBottom: "0rem",
                                }}
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
              ))
            )}
          </div>
        )}
      </div>
    </>
  );
}
