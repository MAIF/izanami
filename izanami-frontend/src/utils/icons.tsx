import * as React from "react";

export function GlobalContextIcon(props: { className?: string }) {
  return (
    <svg
      className={`icon_context ${props.className ? props.className : ""}`}
      height="16px"
      viewBox="0 0 512 512"
    >
      <use xlinkHref="#global-context-icon"></use>
    </svg>
  );
}

export function StringIcon() {
  return (
    <svg
      style={{ marginTop: "-3px" }}
      height="10px"
      viewBox="0 0 8.3977594 4.1816964"
    >
      <use xlinkHref="#string-icon"></use>
    </svg>
  );
}
