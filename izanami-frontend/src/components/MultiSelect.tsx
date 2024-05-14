import * as React from "react";
import Select, { components } from "react-select";
import { customStyles } from "../styles/reactSelect";

export type Option = {
  value: string;
  label: string;
  checked: boolean;
  indeterminate: boolean;
};

interface ISelectProps {
  options: Option[];
  value: Option[];
  onSelected: any;
  defaultValue: Option[];
  labelBy: string;
}
const CHECKBOX_STATES = {
  Checked: "Checked",
  Indeterminate: "Indeterminate",
  Empty: "Empty",
};

const MultiSelect = (props: ISelectProps) => {
  const { options, value, defaultValue, onSelected, labelBy } = props;

  const Option = (props: any) => {
    const { isSelected, data } = props;
    const [state, setState] = React.useState(
      data.indeterminate && isSelected
        ? CHECKBOX_STATES.Indeterminate
        : isSelected && !data.indeterminate
        ? CHECKBOX_STATES.Checked
        : CHECKBOX_STATES.Empty
    );

    const handleChange = () => {
      let updatedChecked =CHECKBOX_STATES.Empty ;
      if (state === CHECKBOX_STATES.Empty) {
        updatedChecked = CHECKBOX_STATES.Checked;
      } else if (state === CHECKBOX_STATES.Indeterminate) {
        updatedChecked = CHECKBOX_STATES.Checked;
      }
      setState(updatedChecked);
    };

    const checkboxRef = React.useRef<HTMLInputElement>(null!);

    React.useEffect(() => {
      checkboxRef.current.checked = state === CHECKBOX_STATES.Checked;
      checkboxRef.current.indeterminate =
        state === CHECKBOX_STATES.Indeterminate;
    }, [state]);

    return (
      <components.Option {...props}>
        <input
          type="checkbox"
          onChange={handleChange}
          ref={checkboxRef}
          style={{
            accentColor: state !== CHECKBOX_STATES.Empty ? "#dc5f9f" : "#fff",
          }}
        />
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
      options={options}
      components={{ Option, MultiValue }}
      value={value ? value : defaultValue}
      defaultValue={defaultValue}
      placeholder={labelBy}
      styles={customStyles}
      onChange={onSelected}
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
