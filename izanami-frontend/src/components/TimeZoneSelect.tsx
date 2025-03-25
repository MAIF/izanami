import * as React from "react";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { DEFAULT_TIMEZONE } from "../utils/datetimeUtils";

// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore
const possibleTimezones: string[] = Intl.supportedValuesOf("timeZone");

export function TimeZoneSelect(props: {
  onChange: (newValue: string) => void;
  value: string;
}) {
  const { value, onChange } = props;
  return (
    <Select
      defaultValue={{
        label: DEFAULT_TIMEZONE,
        value: DEFAULT_TIMEZONE,
      }}
      value={value ? { label: value, value } : undefined}
      onChange={(e) => {
        onChange(e?.value as any);
      }}
      options={possibleTimezones.map((t) => ({
        label: t,
        value: t,
      }))}
      styles={customStyles}
    />
  );
}
