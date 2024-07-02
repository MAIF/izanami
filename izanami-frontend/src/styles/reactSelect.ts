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
  menuList: (styles: object) => ({ ...styles }),
  container: (styles: object) => ({ ...styles }),
  control: (styles: object) => ({
    ...styles,
    backgroundColor: "var(--bg-color_level3) !important",
    color: "var(--color_level3) !important",
    border: "none",
  }),
  menu: (styles: object) => ({
    ...styles,
    backgroundColor: "var(--bg-color_level3) !important",
    color: "var(--color_level3) !important",
    zIndex: 110,
    border: "1px solid #545452",
  }),
  placeholder: (styles: object) => ({
    ...styles,
    color: "var(--color_level3) !important",
  }),
  noOptionsMessage: (styles: object) => ({
    ...styles,
    color: "var(--color_level3) !important",
  }),
  singleValue: (provided: object) => ({
    ...provided,
    color: "var(--color_level3) !important",
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
