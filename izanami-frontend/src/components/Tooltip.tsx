import React, { PropsWithChildren } from "react";
import { Tooltip as ReactTooltip } from "react-tooltip";

export function Tooltip(
  props: PropsWithChildren<{
    position?: "bottom" | "top" | "right" | "left";
    id: string;
    iconClassName?: string;
    borderType?: "info" | "warning" | "error";
    ariaLabel?: string;
  }>,
) {
  const borderType = props.borderType;
  let borderStyle = undefined;
  if (borderType === "error") {
    borderStyle = "border-danger";
  } else if (borderType === "warning") {
    borderStyle = "border-warning";
  } else if (borderType === "info") {
    borderStyle = "border-info";
  }

  const ariaAttributes = props?.ariaLabel
    ? { "aria-label": props.ariaLabel }
    : { "aria-hidden": true };
  const content = (
    <>
      <i
        className={
          props?.iconClassName
            ? props.iconClassName
            : "ms-1 bi bi-question-circle"
        }
        data-tooltip-id={props.id}
        data-tooltip-place={props.position || "top"}
        {...ariaAttributes}
      ></i>
      <ReactTooltip id={props.id}>{props.children}</ReactTooltip>
    </>
  );

  if (borderStyle) {
    return (
      <span
        className={`top-10 align-self-start badge rounded-pill bg-primary-outline ${borderStyle}`}
        role="tooltip"
        style={{
          color: "var(--color-level-3)",
        }}
      >
        {content}
      </span>
    );
  } else {
    return content;
  }
}
