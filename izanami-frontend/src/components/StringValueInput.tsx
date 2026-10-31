import Select from "react-select";
import { customStyles } from "../styles/reactSelect";

export function StringValueInput(props: {
  value?: string;
  onChange: (value: string) => void;
  onBlur: () => void;
  allowedValues?: readonly string[] | null;
  multiline?: boolean;
  inputId?: string;
  ariaLabel: string;
}) {
  const { value, onChange, onBlur, allowedValues, multiline, inputId, ariaLabel } = props;
  if (allowedValues) {
    return (
      <Select
        inputId={inputId}
        aria-label={ariaLabel}
        styles={customStyles}
        placeholder="Choose a value"
        options={allowedValues.map((choice) => ({ value: choice, label: choice }))}
        value={
          value !== undefined && allowedValues.includes(value)
            ? { value, label: value }
            : null
        }
        onChange={(choice) => onChange(choice?.value ?? "")}
        onBlur={onBlur}
      />
    );
  }
  return multiline ? (
    <textarea
      id={inputId}
      aria-label={ariaLabel}
      className="form-control"
      value={value ?? ""}
      onChange={(event) => onChange(event.target.value)}
      onBlur={onBlur}
    />
  ) : (
    <input
      id={inputId}
      aria-label={ariaLabel}
      type="text"
      className="form-control"
      value={value ?? ""}
      onChange={(event) => onChange(event.target.value)}
      onBlur={onBlur}
    />
  );
}
