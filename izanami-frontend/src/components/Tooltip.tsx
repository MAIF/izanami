import React, { PropsWithChildren } from "react";
import { Tooltip as ReactTooltip } from "react-tooltip";

export function Tooltip(
  props: PropsWithChildren<{
    position?: "bottom" | "top" | "right" | "left";
    id: string;
  }>
) {
  return (
    <>
      <i
        className="ms-1 bi bi-question-circle"
        data-tooltip-id={props.id}
        data-tooltip-place={props.position || "top"}
      ></i>
      <ReactTooltip id={props.id}>{props.children}</ReactTooltip>
    </>
  );
}
