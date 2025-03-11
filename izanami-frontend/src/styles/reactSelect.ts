export const customStyles: object = {
  option: (
    provided: object,
    {
      isDisabled,
      isFocused,
      isSelected,
    }: { isDisabled: boolean; isFocused: boolean; isSelected: boolean }
  ) => {
    if (isSelected) {
      return {
        ...provided,
        backgroundColor: isDisabled
          ? null
          : isSelected
          ? "var(--bg-color_level1)  !important"
          : isFocused
          ? "var(--bg-color_level1) !important"
          : null,
        color: isSelected
          ? "var(--color_level1) !important"
          : isFocused
          ? "var(--color_level1) !important"
          : "8C8C8B !important",
      };
    } else {
      return {
        ...provided,
        cursor: isDisabled ? "not-allowed" : "pointer",
        backgroundColor: isDisabled
          ? null
          : isSelected
          ? "var(--bg-color_level3) !important"
          : isFocused
          ? "var(--bg-color_level2)  !important"
          : null,
        color: isSelected
          ? "var(--color_level1) !important"
          : isFocused
          ? "var(--color_level1) !important"
          : "8C8C8B !important",
      };
    }
  },
  indicatorSeparator: (
    styles: object,
    { isDisabled }: { isDisabled: boolean }
  ) => {
    if (isDisabled) {
      return {
        ...styles,
        backgroundColor: "var(--bg-color_level3) !important",
      };
    } else {
      return styles;
    }
  },
  dropdownIndicator: (
    styles: object,
    { isDisabled }: { isDisabled: boolean }
  ) => {
    if (isDisabled) {
      return {
        ...styles,
        color: "var(--bg-color_level3) !important",
      };
    } else {
      return styles;
    }
  },
  menuList: (styles: object) => ({ ...styles }),
  container: (styles: object) => ({ ...styles }),
  control: (styles: object, { isDisabled }: { isDisabled: boolean }) => ({
    ...styles,
    backgroundColor: isDisabled
      ? "var(--bg-color_level2) !important"
      : "var(--bg-color_level3) !important",
    color: "var(--color_level3) !important",
    border: isDisabled ? "1px solid var(--bg-color_level3)" : "none",
  }),
  menu: (styles: object) => ({
    ...styles,
    backgroundColor: "var(--bg-color_level3) !important",
    color: "var(--color_level3) !important",
    zIndex: 110,
    border: "1px solid #545452",
  }),
  placeholder: (styles: object, { isDisabled }: { isDisabled: boolean }) => ({
    ...styles,
    color: isDisabled
      ? "var(--color_level2) !important"
      : "var(--color_level3) !important",
    fontStyle: "italic",
  }),
  noOptionsMessage: (styles: object) => ({
    ...styles,
    color: "var(--color_level3) !important",
  }),
  singleValue: (provided: object, { isDisabled }: { isDisabled: boolean }) => ({
    ...provided,
    color: isDisabled
      ? "var(--color_level1) !important"
      : "var(--color_level3) !important",
    backgroundColor: null,
  }),
  multiValue: (styles: object) => {
    return {
      ...styles,
      backgroundColor: "var(--bg-color_level3) !important",
    };
  },
  multiValueLabel: (styles: object, { data }: any) => ({
    ...styles,
    color: data.color,
    backgroundColor: "var(--bg-color_level2) !important",
  }),
  multiValueRemove: (styles: object) => ({
    ...styles,
    backgroundColor: "var(--bg-color_level2) !important",
    ":hover": {
      backgroundColor: "#D5443F !important",
      color: "#FFF !important",
    },
  }),
};
