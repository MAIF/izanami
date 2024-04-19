import * as React from "react";
import { useQuery } from "react-query";
import { useParams } from "react-router-dom";
import { findFeatures, tenantFeaturesKey } from "../utils/queries";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { Loader } from "./Loader";

export function FeatureSelector(props: {
  value?: string;
  onChange?: (v: string[]) => void;
}) {
  const { tenant } = useParams();
  const { value, onChange } = props;

  const featureQuery = useQuery(tenantFeaturesKey(tenant!), () =>
    findFeatures(tenant!)
  );

  if (featureQuery.error) {
    return <div>Failed to fetch features</div>;
  } else if (featureQuery.data) {
    const options = featureQuery.data.map(({ name, id, project }) => ({
      value: id,
      label: `${name} (${project})`,
    }));

    return (
      <Select
        isMulti
        value={
          value
            ? options.find(({ value }) => props.value?.includes(value ?? ""))
            : undefined
        }
        onChange={(newValue) => {
          onChange?.(newValue.map(({ value }) => value!));
        }}
        styles={customStyles}
        options={options}
        isClearable
      />
    );
  } else {
    return <Loader message="Loading features..." />;
  }
}
