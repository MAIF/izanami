import React, { ReactElement, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { searchEntitiesByTenant, searchEntities } from "../../utils/queries";
import { GlobalContextIcon } from "../../utils/icons";
import { SearchResult } from "../../utils/types";
import { debounce } from "lodash";

interface ISearchProps {
  tenant?: string;
  onClose: () => void;
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

const getLinkPath = (item: SearchResult) => {
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

type SearchModalStatus = { all: true } | { all: false; tenant: string };
type SearchResultStatus =
  | { state: "SUCCESS"; results: SearchResult[] }
  | { state: "ERROR"; error: string }
  | { state: "PENDING" }
  | { state: "INITIAL" };

export function SearchModalContent({ tenant, onClose }: ISearchProps) {
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
  return (
    <>
      {tenant && tenant !== "all" && (
        <div
          className="d-flex flex-row align-items-start my-1"
          role="group"
          aria-label="Tenant selection"
        >
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
        </div>
      )}
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
              if (event.target.value) {
                setResultStatus({ state: "PENDING" });
                if (modalStatus.all) {
                  searchEntities(event.target.value)
                    .then((r) =>
                      setResultStatus({ state: "SUCCESS", results: r })
                    )
                    .catch(() => {
                      setResultStatus({
                        state: "ERROR",
                        error: "There was an error fetching data",
                      });
                    });
                } else {
                  searchEntitiesByTenant(modalStatus.tenant, event.target.value)
                    .then((r) =>
                      setResultStatus({ state: "SUCCESS", results: r })
                    )
                    .catch(() =>
                      setResultStatus({
                        state: "ERROR",
                        error: "There was an error fetching data",
                      })
                    );
                }
              } else if (inputRef.current) {
                inputRef.current.value = "";
                setResultStatus({ state: "INITIAL" });
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
                if (inputRef?.current?.value) {
                  inputRef.current.value = "";
                  setResultStatus({ state: "INITIAL" });
                }
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
