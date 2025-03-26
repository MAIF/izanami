import React, { useState, useEffect, useContext } from "react";
import { Link } from "react-router-dom";
import { SearchResult } from "../../utils/types";
import {
  getLinkPath,
  typeDisplayInformation,
  SearchResultStatus,
  SearchModalStatus,
} from "./searchUtils";
import { IzanamiContext } from "../../securityContext";

interface SearchResultsProps {
  modalStatus: SearchModalStatus;
  groupedItems: Map<string, SearchResult[]>;
  resultStatus: SearchResultStatus;
  filterOptions: string[];
  onClose: () => void;
}
export function SearchResults({
  modalStatus,
  groupedItems,
  resultStatus,
  filterOptions,
  onClose,
}: SearchResultsProps) {
  const { user } = useContext(IzanamiContext);
  const username = user?.username ?? "";
  const [searchHistory, setSearchHistory] = useState<SearchResult[]>([]);
  const allHistory = JSON.parse(
    localStorage.getItem("userSearchHistory") || "{}"
  );

  useEffect(() => {
    if (allHistory[username]) {
      setSearchHistory(allHistory[username]);
    }
  }, []);

  const handleItemClick = (item: SearchResult) => {
    const tenantExists = item.path.some((element) => element.type === "tenant");

    if (!tenantExists) {
      item.path = [...item.path, { type: "tenant", name: item.tenant }];
    }

    setSearchHistory((prevHistory) => {
      const userHistory = allHistory[username] || [];
      const updatedHistory = Array.from(
        new Map(
          [...userHistory, ...prevHistory, item].map((item) => [
            item.name + item.type + item.tenant, // better to have item id
            item,
          ])
        ).values()
      ).slice(-30);
      allHistory[username] = updatedHistory;
      localStorage.setItem("userSearchHistory", JSON.stringify(allHistory));

      return updatedHistory;
    });
    onClose();
  };
  const filteredHistory = searchHistory
    .filter(
      (item) =>
        (modalStatus.all || item.tenant === modalStatus.tenant) &&
        (filterOptions.length === 0 || filterOptions.includes(item.type))
    )
    .slice(-5);

  if (resultStatus.state === "INITIAL" && filteredHistory.length > 0) {
    return (
      <div className="search-container">
        <div className="search-result">
          <>
            <div className="d-flex justify-content-between align-items-center">
              <span>Previous searches</span>
              <button
                className="clear-history-search-btn"
                onClick={() => {
                  setSearchHistory([]);
                  delete allHistory[username];
                  localStorage.setItem(
                    "userSearchHistory",
                    JSON.stringify(allHistory)
                  );
                }}
              >
                Clear
              </button>
            </div>

            <ul className="search-ul nav flex-column">
              {filteredHistory.map((term, index) => (
                <>
                  <li className="search-ul-item" key={index}>
                    <button
                      type="button"
                      id={`clear-history-item-${index}`}
                      onClick={(e) => {
                        e.preventDefault();
                        setSearchHistory((prevHistory) => {
                          const updatedHistory = prevHistory.filter(
                            (item) => item !== term
                          );
                          allHistory[username] = updatedHistory;
                          localStorage.setItem(
                            "userSearchHistory",
                            JSON.stringify(allHistory)
                          );
                          return updatedHistory;
                        });
                      }}
                      aria-label="Clear search"
                      className="clear-history-item-btn"
                    >
                      <i className="fa-regular fa-circle-xmark" />
                    </button>
                    <Link
                      to={getLinkPath(term) || "#"}
                      onClick={() => {
                        handleItemClick(term);
                      }}
                    >
                      <ul
                        className="breadcrumb"
                        style={{
                          marginBottom: "0rem",
                        }}
                      >
                        {modalStatus.all &&
                          term.path.map((pathElement, pathIndex) => (
                            <li
                              className="breadcrumb-item"
                              key={`${term.name}-${pathIndex}`}
                            >
                              {typeDisplayInformation
                                .get(pathElement.type)
                                ?.icon() ?? ""}
                              {pathElement.name}
                            </li>
                          ))}
                        <li
                          className="breadcrumb-item"
                          key={`${term.name}-name`}
                        >
                          {typeDisplayInformation.get(term.type)?.icon() ?? ""}
                          {term.name}
                        </li>
                      </ul>
                    </Link>
                  </li>
                </>
              ))}
            </ul>
          </>
        </div>
      </div>
    );
  }

  if (resultStatus.state === "ERROR") {
    return (
      <div className="search-container">
        <div className="search-result">
          <div>{resultStatus.error}</div>
        </div>
      </div>
    );
  }

  if (resultStatus.state === "PENDING") {
    return (
      <div className="search-container">
        <div className="search-result">
          <div>Searching...</div>
        </div>
      </div>
    );
  }

  if (resultStatus.state === "SUCCESS" && groupedItems?.size > 0) {
    return (
      <div className="search-container">
        <div className="search-result">
          <ul className="search-ul nav flex-column">
            {[...groupedItems.keys()].map((originTable, originTableIndex) => (
              <li className="search-ul-item" key={originTable}>
                <span>
                  {typeDisplayInformation.get(originTable)?.displayName ?? ""}
                </span>
                {groupedItems.get(originTable)?.map((item, index) => (
                  <ol
                    className="search-ul nav flex-column"
                    key={`${item.name}-${originTableIndex}-${index}`}
                  >
                    <li className="search-ul-item">
                      <Link
                        to={getLinkPath(item) || "#"}
                        onClick={() => handleItemClick(item)}
                      >
                        <ul
                          className="breadcrumb"
                          style={{
                            marginBottom: "0rem",
                          }}
                        >
                          {item.path.map((pathElement, pathIndex) => (
                            <li
                              className="breadcrumb-item"
                              key={`${item.name}-${pathIndex}`}
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
                            {typeDisplayInformation.get(item.type)?.icon() ??
                              ""}
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
        </div>
      </div>
    );
  }

  return (
    <div className="search-container">
      <div className="search-result">
        <div className="search-result">No results found</div>
      </div>
    </div>
  );
}
