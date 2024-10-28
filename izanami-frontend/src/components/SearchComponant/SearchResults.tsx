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
  onClose: () => void;
}

export function SearchResults({
  modalStatus,
  groupedItems,
  resultStatus,
  onClose,
}: SearchResultsProps) {
  const { user } = useContext(IzanamiContext);
  const username = user?.username ?? "";
  const [searchHistory, setSearchHistory] = useState<
    { type: string; tenant: string; name: string }[]
  >([]);
  useEffect(() => {
    const allHistory = JSON.parse(
      localStorage.getItem("userSearchHistory") || "{}"
    );
    if (allHistory[username]) {
      setSearchHistory(allHistory[username]);
    }
  }, []);

  const handleItemClick = (item: SearchResult) => {
    setSearchHistory((prevHistory) => {
      const allHistory = JSON.parse(
        localStorage.getItem("userSearchHistory") || "{}"
      );
      const userHistory = allHistory[username] || [];
      const updatedHistory = Array.from(
        new Map(
          [...userHistory, ...prevHistory, item].map((item) => [
            item.name,
            item,
          ])
        ).values()
      ).slice(0, 5);
      allHistory[username] = updatedHistory;
      localStorage.setItem("userSearchHistory", JSON.stringify(allHistory));

      return updatedHistory;
    });
    onClose();
  };
  const filteredHistory = searchHistory.filter(
    (item) => modalStatus.all || item.tenant === modalStatus.tenant
  );

  return (
    <div className="search-container">
      <div className="search-result">
        {filteredHistory.length > 0 && (
          <>
            <span>Recent</span>
            <ul className="search-ul nav flex-column">
              {filteredHistory.map((term, index) => (
                <li className="search-ul-item" key={index}>
                  <i className="fas fa-search me-2" aria-hidden="true" />
                  {term.type}:{" "}
                  {modalStatus.all
                    ? `${term.tenant} / ${term.name}`
                    : term.name}
                </li>
              ))}
            </ul>
            <hr />
          </>
        )}
        {resultStatus.state === "ERROR" ? (
          <div>{resultStatus.error}</div>
        ) : resultStatus.state === "PENDING" ? (
          <div>Searching...</div>
        ) : (
          resultStatus.state === "SUCCESS" &&
          (groupedItems?.size > 0 ? (
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
          ) : (
            <div className="search-result">No results found</div>
          ))
        )}
      </div>
    </div>
  );
}
