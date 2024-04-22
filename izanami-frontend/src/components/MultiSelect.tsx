import * as React from "react";
import Select, { components } from "react-select";
import { customStyles } from "../styles/reactSelect";

export type Option = {
  value: string;
  label: string;
  state: boolean;
};
const MultiSelect = (props: any) => {
  const Option = (props: any) => {
    return (
      <components.Option {...props}>
        <input
          type="checkbox"
          checked={props.isSelected}
          onChange={() => null}
          style={{
            accentColor: props.isSelected ? "#dc5f9f" : "#fff",
          }}
        />{" "}
        <label style={{ marginLeft: "5px" }}>{props.label}</label>
      </components.Option>
    );
  };

  const MultiValue = (props: any) => {
    return (
      <components.MultiValue {...props}>
        <span>{props.data.label}</span>
      </components.MultiValue>
    );
  };

  return (
    <Select
      {...props}
      options={[...props.options]}
      onChange={props.onChange}
      components={{ Option, MultiValue }}
      value={props.values}
      styles={customStyles}
      isMulti
      closeMenuOnSelect={false}
      tabSelectsValue={false}
      backspaceRemovesValue={false}
      hideSelectedOptions={false}
      blurInputOnSelect={false}
    />
  );
};

export default MultiSelect;
