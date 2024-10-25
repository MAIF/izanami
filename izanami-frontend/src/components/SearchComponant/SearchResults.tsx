import React, { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import { SearchResult } from "../../utils/types";
import {
  getLinkPath,
  typeDisplayInformation,
  SearchResultStatus,
} from "../../utils/searchUtils";

interface SearchResultsProps {
  groupedItems: Map<string, SearchResult[]>;
  resultStatus: SearchResultStatus;
  onClose: () => void;
}

export function SearchResults({
  groupedItems,
  resultStatus,
  onClose,
}: SearchResultsProps) {
  const [searchHistory, setSearchHistory] = useState<string[]>([]);
  useEffect(() => {
    const oldHistory = localStorage.getItem("searchHistory");
    if (oldHistory) {
      setSearchHistory(JSON.parse(oldHistory));
    }
  }, []);

  const handleItemClick = (item: SearchResult) => {
    setSearchHistory((prevHistory: string[]) => {
      const oldSearchHistory = localStorage.getItem("searchHistory");
      const parsedOldHistory: string[] = oldSearchHistory
        ? JSON.parse(oldSearchHistory)
        : [];

      const updatedHistory = Array.from(
        new Set([item.name, ...prevHistory, ...parsedOldHistory])
      ).slice(0, 10);
      localStorage.setItem("searchHistory", JSON.stringify(updatedHistory));
      return updatedHistory;
    });
    onClose();
  };

  return (
    <div className="search-container">
      <div className="search-result">
        <span>Recent</span>
        <ul>
          {searchHistory.map((term, index) => (
            <li key={index}>{term}</li>
          ))}
        </ul>

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
