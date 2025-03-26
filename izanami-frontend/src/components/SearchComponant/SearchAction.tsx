import * as React from "react";
import Select, { components } from "react-select";
import { customStyles } from "../../styles/reactSelect";
import { SearchModalStatus, Option } from "./searchUtils";
interface SearchActionProps {
  tenant?: string;
  allTenants?: string[];
  modalStatus: { all: boolean; tenant?: string };
  setModalStatus: (status: SearchModalStatus) => void;
  filterOptions: { value: string; label: string }[];
  setFilters: (filters: { value: string; label: string }[]) => void;
}

const CustomOption = (props: any) => {
  return (
    <components.Option {...props}>
      <span style={{ marginRight: 1 }}>{props.data.icon}</span>
      {props.data.label}
    </components.Option>
  );
};

export function SearchAction({
  tenant,
  allTenants,
  modalStatus,
  setModalStatus,
  filterOptions,
  setFilters,
}: SearchActionProps) {
  return (
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
        {tenant && (
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
          components={{ Option: CustomOption }}
          styles={customStyles}
          isMulti
          isClearable
          placeholder="Apply filter"
        />
      </div>
    </div>
  );
}
