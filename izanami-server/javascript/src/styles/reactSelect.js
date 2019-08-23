export const customStyles = {
  option: (provided, { data, isDisabled, isFocused, isSelected }) => {
    if (isSelected) {
      return {
        ...provided,
        backgroundColor: isDisabled
          ? null
          : isSelected
          ? "#494948"
          : isFocused
          ? "#373735"
          : null,
        color: isSelected ? "#fff" : isFocused ? "#fff" : "8C8C8B"
      };
    } else {
      return { ...provided };
    }
  },
  menuList: styles => ({ ...styles }),
  container: styles => ({ ...styles }),
  control: styles => ({
    ...styles,
    backgroundColor: "#494948",
    color: "#fff",
    border: "none"
  }),
  menu: styles => ({
    ...styles,
    backgroundColor: "#373735",
    color: "#8C8C8B",
    zIndex: 110,
    border: "1px solid #fff"
  }),
  singleValue: provided => ({
    ...provided,
    color: "#fff",
    backgroundColor: null
  })
};
