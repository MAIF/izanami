import * as React from "react";
import { useQuery } from "react-query";
import { useParams } from "react-router-dom";
import { queryTenant, tenantQueryKey } from "../utils/queries";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { Loader } from "./Loader";

export function ProjectSelector(props: {
  value?: string;
  onChange?: (v: string[]) => void;
}) {
  const { tenant } = useParams();
  const { value, onChange } = props;

  const tenantQuery = useQuery(tenantQueryKey(tenant!), () =>
    queryTenant(tenant!)
  );

  if (tenantQuery.error) {
    return <div>Failed to fetch projects</div>;
  } else if (tenantQuery.data) {
    const options =
      tenantQuery.data.projects?.map(({ name, id }) => ({
        value: id,
        label: name,
      })) || [];

    return (
      <Select
        isMulti
        value={
          value
            ? options.find(({ value }) => props.value?.includes(value ?? ""))
            : undefined
        }
        onChange={(newValue) => {
          onChange?.(newValue.map(({ value }) => value));
        }}
        styles={customStyles}
        options={options}
        isClearable
      />
    );
  } else {
    return <Loader message="Loading projects..." />;
  }
}
