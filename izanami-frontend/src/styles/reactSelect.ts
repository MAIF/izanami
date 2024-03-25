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
          ? "var(--bg-color_level3) !important"
          : isFocused
          ? "red !important"
          : null,
        color: isSelected
          ? "#FFF !important"
          : isFocused
          ? "#FFF !important"
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
          ? "#FFF !important"
          : isFocused
          ? "#FFF !important"
          : "8C8C8B !important",
      };
    }
  },
  menuList: (styles: object) => ({ ...styles }),
  container: (styles: object) => ({ ...styles }),
  control: (styles: object) => ({
    ...styles,
    backgroundColor: "var(--bg-color_level3) !important",
    color: "#FFF !important",
    border: "none",
  }),
  menu: (styles: object) => ({
    ...styles,
    backgroundColor: "var(--bg-color_level3) !important",
    color: "#FFF !important",
    zIndex: 110,
    border: "1px solid #545452",
  }),
  placeholder: (styles: object) => ({
    ...styles,
    color: "var(--color_level3) !important",
  }),
  singleValue: (provided: object) => ({
    ...provided,
    color: "#FFF !important",
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
