import React, { useState, useEffect, useContext } from "react";
import { Link } from "react-router-dom";
import { SearchResult } from "../../utils/types";
import {
  getLinkPath,
  typeDisplayInformation,
  SearchResultStatus,
  SearchModalStatus,
} from "../../utils/searchUtils";
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
  useEffect(() => {
    const allHistory = JSON.parse(
      localStorage.getItem("userSearchHistory") || "{}"
    );
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
      const allHistory = JSON.parse(
        localStorage.getItem("userSearchHistory") || "{}"
      );
      const userHistory = allHistory[username] || [];
      const updatedHistory = Array.from(
        new Map(
          [...userHistory, ...prevHistory, item].map((item) => [
            item.name + item.type + item.tenant, // better to have item id
            item,
          ])
        ).values()
      );
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

  return (
    <div className="search-container">
      {filteredHistory.length > 0 && resultStatus.state == "INITIAL" && (
        <div className="search-result">
          <>
            <span>Recent</span>
            <ul className="search-ul nav flex-column">
              {filteredHistory.map((term, index) => (
                <li className="search-ul-item" key={index}>
                  <Link
                    to={getLinkPath(term) || "#"}
                    onClick={() => handleItemClick(term)}
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
                      <li className="breadcrumb-item" key={`${term.name}-name`}>
                        {typeDisplayInformation.get(term.type)?.icon() ?? ""}
                        {term.name}
                      </li>
                    </ul>
                  </Link>
                </li>
              ))}
            </ul>
          </>
        </div>
      )}

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
                {[...groupedItems.keys()].map(
                  (originTable, originTableIndex) => (
                    <li className="search-ul-item" key={originTable}>
                      <span>
                        {typeDisplayInformation.get(originTable)?.displayName ??
                          ""}
                      </span>
                      {groupedItems.get(originTable)?.map((item, index) => (
                        <ol
                          className="search-ul nav flex-column"
                          key={`${item.name}-${originTableIndex}-${index}`}
                        >
                          <li
                            className="search-ul-item"
                            key={`${item.name}-${originTable}-${index}`}
                          >
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
                  )
                )}
              </ul>
            ) : (
              <div className="search-result">No results found</div>
            ))
          )}
        </div>
      )}
    </div>
  );
}
