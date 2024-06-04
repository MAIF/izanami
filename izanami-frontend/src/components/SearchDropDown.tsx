import React, { useState, useEffect } from "react";
import { useDebounce } from "./useDebounce";
import { searchEntitiesByTenant, searchQueryEntities } from "../utils/queries";
import { useQuery } from "react-query";
import { searchQueryByTenant, searchEntities } from "../utils/queries";

interface ISearchProps {
  tenant: string | undefined;
  user: string;
}

export function SearchDropDown(props: ISearchProps) {
  const { tenant, user } = props;
  const [searchQuery, setSearchQuery] = useState("");
  const debouncedSearchQuery = useDebounce(searchQuery, 500);
  const iconMapping = new Map([
    ["features", "fa-gears"],
    ["projects", "fa-building"],
    ["apikeys", "fa-key"],
    ["tags", "fa-tag"],
    ["tenants", "fa-cloud"],
    ["users", "fa-user"],
  ]);
  const useSearchEntitiesByTenant = (query: string) => {
    if (tenant) {
      return useQuery(
        searchQueryByTenant(tenant, query),
        () => searchEntitiesByTenant(tenant!, query),
        {
          enabled: !!query,
        }
      );
    }
    return useQuery(
      searchQueryEntities(query),
      () => searchEntities(user, query),
      {
        enabled: !!query,
      }
    );
  };
  const {
    data: SearchItems,
    isLoading,
    isSuccess,
    isError,
  } = useSearchEntitiesByTenant(debouncedSearchQuery);
  const [showDropdown, setShowDropdown] = useState(false);

  const handleSearchInput = (event: any) => {
    setSearchQuery(event.target.value);
    setShowDropdown(false);
  };

  useEffect(() => {
    if (debouncedSearchQuery.trim() !== "") {
      setShowDropdown(true);
    } else {
      setShowDropdown(false);
    }
  }, [debouncedSearchQuery]);

  const handleItemClick = (item: any) => {
    //setSearchQuery(item);
    console.log(item);
    setShowDropdown(false);
  };

  const handleClickOutside = (event: any) => {
    if (!event.target.closest(".search-container")) {
      setShowDropdown(false);
    }
  };
  useEffect(() => {
    document.addEventListener("click", handleClickOutside);
    return () => {
      document.removeEventListener("click", handleClickOutside);
    };
  }, []);
  const groupedItems = SearchItems?.reduce((acc: any, item: any) => {
    if (!acc[item.origin_table]) {
      acc[item.origin_table] = [];
    }
    acc[item.origin_table].push(item);
    return acc;
  }, {});

  return (
    <div className="search-container">
      <div>
        <i className="fas fa-search search-icon" />
        <input
          type="text"
          value={searchQuery}
          onChange={handleSearchInput}
          placeholder="Type to search..."
          className="form-control"
          style={{ padding: ".375rem 1.85rem" }}
        />
      </div>
      {showDropdown && (
        <div className="dropdown-content">
          {isLoading && <div className="dropdown-item">Loading...</div>}
          {isError && (
            <div className="dropdown-item">
              There was an error for fetching data.
            </div>
          )}
          {isSuccess &&
            (Object.keys(groupedItems).length > 0 ? (
              Object.keys(groupedItems).map((originTable) => (
                <div key={originTable} className="dropdown-group">
                  <div className="dropdown-group-title">{originTable}</div>
                  {groupedItems[originTable].map((item: any) => (
                    <div
                      key={item.id}
                      onClick={() => handleItemClick(item)}
                      className="dropdown-item"
                    >
                      <span
                        className={`fas ${iconMapping.get(
                          originTable
                        )} icon-item`}
                      />
                      {item.name}
                    </div>
                  ))}
                </div>
              ))
            ) : (
              <div className="dropdown-item">No results found</div>
            ))}
        </div>
      )}
    </div>
  );
}
