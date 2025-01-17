import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import {
  findFeatures,
  projectQueryKey,
  queryProject,
  tenantFeaturesKey,
} from "../utils/queries";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { Loader } from "./Loader";
import CreatableSelect from "react-select/creatable";

export function FeatureSelector(props: {
  id?: string;
  value?: string[];
  onChange?: (v: string[]) => void;
  project?: string;
  creatable?: boolean;
}) {
  const { tenant } = useParams();
  const { onChange, project } = props;
  const creatable = props.creatable || false;

  const featureQuery = useQuery({
    queryKey:
      project !== undefined
        ? [projectQueryKey(tenant!, project), "features"]
        : [tenantFeaturesKey(tenant!)],

    queryFn: () =>
      project
        ? queryProject(tenant!, project).then((p) => p.features)
        : findFeatures(tenant!),
  });

  if (featureQuery.error) {
    return <div>Failed to fetch features</div>;
  } else if (featureQuery.data) {
    const options = featureQuery.data.map(({ name, id, project }) => ({
      value: id,
      label: `${name}${props.project ? "" : ` (${project})`} `,
    }));

    const selectProps = {
      inputId: props.id ?? undefined,
      isMulti: true,
      value: props.value
        ? props.value.map((v) => {
            const maybeOption = options.find(({ value }) => value === v);
            if (maybeOption) {
              return maybeOption;
            } else {
              return { label: v, value: v };
            }
          })
        : undefined,
      onChange: (newValue: any[]) => {
        console.log("newValue", newValue);
        onChange?.(newValue.map(({ value }) => value!));
      },
      styles: customStyles,
      options: options,
      isClearable: true,
    };

    return creatable ? (
      <CreatableSelect {...(selectProps as any)} />
    ) : (
      <Select {...(selectProps as any)} />
    );
  } else {
    return <Loader message="Loading features..." />;
  }
}
