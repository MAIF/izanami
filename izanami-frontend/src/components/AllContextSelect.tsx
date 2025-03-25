import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { globalContextKey, queryGlobalContexts } from "../utils/queries";
import { useParams } from "react-router-dom";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { GlobalContextIcon } from "../utils/icons";
import { Loader } from "./Loader";
import { TContext } from "../utils/types";
import { possiblePaths } from "../utils/contextUtils";

export function AllContexts(props: {
  id: string;
  value?: string;
  onChange: (v?: string) => void;
}) {
  const { tenant } = useParams();
  const { value, onChange } = props;
  const contextQuery = useQuery({
    queryKey: [globalContextKey(tenant!)],

    queryFn: () => queryGlobalContexts(tenant!, true),
  });

  if (contextQuery.error) {
    return <div>Failed to fetch contexts</div>;
  } else if (contextQuery.data) {
    const options = contextHierarchyToSelectOption(contextQuery.data);
    return (
      <Select
        inputId={props.id}
        value={
          value ? options.find(({ value }) => value === props.value) : undefined
        }
        onChange={(newValue) => {
          onChange?.(newValue?.value);
        }}
        styles={customStyles}
        options={options}
        isClearable
      />
    );
  } else {
    return <Loader message="Loading contexts..." />;
  }
}

function contextHierarchyToSelectOption(contextHierarchy: TContext[]) {
  return possiblePaths(contextHierarchy)
    .sort((context1, context2) => {
      if (context1.context.global && !context2.context.global) {
        return -1;
      } else if (context2.context.global && !context1.context.global) {
        return 1;
      } else {
        return context1.path.localeCompare(context2.path);
      }
    })
    .map(({ context, path }) => {
      let label = undefined;
      if (context.project) {
        label = (
          <>
            {path} (<i className="fas fa-building" aria-hidden></i>&nbsp;
            {context.project})
          </>
        );
      } else {
        label = (
          <>
            <GlobalContextIcon />
            &nbsp; {path}
          </>
        );
      }

      return { label, value: path };
    });
}
