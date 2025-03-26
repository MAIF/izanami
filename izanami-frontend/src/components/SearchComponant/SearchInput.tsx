import React, { useRef, useState } from "react";
import { SearchResultStatus } from "./searchUtils";

interface SearchInputProps {
  modalStatus: { all: boolean; tenant?: string };
  setSearchTerm: (term: string) => void;
  setResultStatus: (result: SearchResultStatus) => void;
}

const SearchInput: React.FC<SearchInputProps> = ({
  modalStatus,
  setSearchTerm,
  setResultStatus,
}) => {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [searchTerm, setLocalSearchTerm] = useState("");

  const handleSearchChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const term = event.target.value;
    setSearchTerm(term);
    setLocalSearchTerm(term);
    if (!term) {
      clearInput();
    }
  };
  const clearInput = () => {
    if (inputRef.current) {
      inputRef.current.value = "";
      setResultStatus({ state: "INITIAL" });
    }
  };
  return (
    <div className="search-container-input">
      <i className="fas fa-search search-icon" aria-hidden="true" />
      <input
        ref={inputRef}
        type="text"
        id="search-input"
        name="search-form"
        title="Search in tenants"
        onChange={handleSearchChange}
        placeholder={`Search in ${
          modalStatus.all ? "all tenants" : `this tenant: ${modalStatus.tenant}`
        }`}
        aria-label={`Search in ${
          modalStatus.all ? "all tenants" : `this tenant: ${modalStatus.tenant}`
        }`}
        className="form-control"
        style={{ padding: ".375rem 1.85rem" }}
        autoFocus
      />
      {searchTerm && (
        <button
          type="button"
          onClick={clearInput}
          aria-label="Clear search"
          className="clear-search-btn"
        >
          <i className="fa-regular fa-circle-xmark" />
        </button>
      )}
    </div>
  );
};

export default SearchInput;
