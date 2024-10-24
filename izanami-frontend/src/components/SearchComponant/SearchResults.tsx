import React from "react";
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
  return (
    <div className="search-result">
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
                        onClick={() => onClose()}
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
  );
}
