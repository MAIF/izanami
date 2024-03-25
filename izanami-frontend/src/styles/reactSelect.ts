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
          ? "#494948 !important"
          : isFocused
          ? "#373735 !important"
          : null,
        color: isSelected ? "#FFF !important" : isFocused ? "#FFF !important" : "8C8C8B !important",
      };
    } else {
      return {
        ...provided,
        backgroundColor: isDisabled
          ? null
          : isSelected
          ? "#494948 !important"
          : isFocused
          ? "#545452 !important"
          : null,
          color: isSelected ? "#FFF !important" : isFocused ? "#FFF !important" : "8C8C8B !important",
      };
    }
  },
  menuList: (styles: object) => ({ ...styles }),
  container: (styles: object) => ({ ...styles }),
  control: (styles: object) => ({
    ...styles,
    backgroundColor: "#494948 !important",
    color: "#FFF !important",
    border: "none",
  }),
  menu: (styles: object) => ({
    ...styles,
    backgroundColor: "#373735 !important",
    color: "#FFF !important",
    zIndex: 110,
    border: "1px solid #545452",
  }),
  singleValue: (provided: object) => ({
    ...provided,
    color: "#FFF !important",
    backgroundColor: null,
  }),
  multiValue: (styles: object) => {
    return {
      ...styles,
      backgroundColor: "#535352 !important",
    };
  },
  multiValueLabel: (styles: object, { data }: any) => ({
    ...styles,
    color: data.color,
  }),
  multiValueRemove: (styles: object) => ({
    ...styles,
    ":hover": {
      backgroundColor: "#D5443F !important",
      color: "#FFF !important",
    },
  }),
};
