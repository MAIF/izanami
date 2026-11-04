import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { StringAllowedValue } from "../utils/types";

export function StringValueInput(props: {
  value?: string;
  onChange: (value: string) => void;
  onBlur: () => void;
  allowedValues?: readonly StringAllowedValue[] | null;
  multiline?: boolean;
  inputId?: string;
  ariaLabel: string;
}) {
  const { value, onChange, onBlur, allowedValues, multiline, inputId, ariaLabel } = props;
  if (allowedValues) {
    const choices = allowedValues.filter(
      (choice, index) =>
        choice.value.trim().length > 0 &&
        allowedValues.findIndex((other) => other.value === choice.value) === index,
    );
    const options = choices.map((choice) => ({
      value: choice.value,
      label: choice.label?.trim() ? choice.label : choice.value,
    }));
    return (
      <Select
        inputId={inputId}
        aria-label={ariaLabel}
        styles={customStyles}
        placeholder="Choose a value"
        options={options}
        formatOptionLabel={({ value: choice, label }) => {
          const labeled = allowedValues.find((item) => item.value === choice)?.label?.trim();
          const text = label.replace(/\s+/g, " ");
          const needsPreview = text.length > 80 || /[\r\n]/.test(label);
          const preview = text.length > 80 ? `${text.slice(0, 80)}...` : text;
          return (
            <span className="d-block text-truncate" title={choice}>
              {!labeled && (
                <span style={{ opacity: 0.6, pointerEvents: "none" }}>{"<no label> "}</span>
              )}
              {needsPreview && !labeled ? (
                <>
                  <span style={{ opacity: 0.6, pointerEvents: "none" }}>
                    {allowedValues.findIndex((item) => item.value === choice) + 1}.{" "}
                  </span>
                  {preview}
                </>
              ) : needsPreview || labeled ? (
                preview
              ) : (
                choice
              )}
            </span>
          );
        }}
        value={options.find((choice) => choice.value === value) ?? null}
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
