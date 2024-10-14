import * as React from "react";
import { FeatureTypeName } from "../utils/types";
import { StringIcon } from "../utils/icons";

const NumberTypeIcon = () => (
  <svg
    xmlns=" http://www.w3.org/2000/svg"
    viewBox="0 0 301.95 240.38"
    width="18"
    height="18"
    style={{ marginTop: "-2px" }}
  >
    <g id="Calque_2" data-name="Calque 2">
      <g id="Calque_1-2" data-name="Calque 1">
        <polygon
          className="cls-1"
          points="88.9 98.07 45.19 98.07 23.98 118.4 45.19 138.73 88.9 138.73 110.11 118.4 88.9 98.07"
        />
        <polygon
          className="cls-1"
          points="20.89 121.36 0 141.38 0 212.86 42.08 193.62 42.08 141.67 20.89 121.36"
        />
        <polygon
          className="cls-1"
          points="20.89 115.44 42.08 95.13 42.08 46.72 0 27.47 0 95.42 20.89 115.44"
        />
        <path
          className="cls-1"
          d="M92,42.08h0V95.13l21.19,20.31,20.89-20V22.77L111.32,0,92,42.07Z"
        />
        <path
          className="cls-1"
          d="M87.41,198.29H42.16L0,217.57v0l22.78,22.78h83.87L87.42,198.3Z"
        />
        <path
          className="cls-1"
          d="M113.2,121.36,92,141.67V198l19.33,42.3,22.76-22.76V141.37l-20.89-20Z"
        />
        <path
          className="cls-1"
          d="M42.24,42.08h45L106.62,0H22.78L0,22.77,42.24,42.09Z"
        />
        <polygon
          className="cls-1"
          points="256.75 98.07 213.05 98.07 191.83 118.4 213.05 138.73 256.75 138.73 277.97 118.4 256.75 98.07"
        />
        <path
          className="cls-1"
          d="M259.87,45.11v50l21.19,20.31,20.89-20V22.77l-9.87-9.87Z"
        />
        <path
          className="cls-1"
          d="M209.94,45.11,177.73,12.9l-9.87,9.87V95.42l20.89,20,21.19-20.31Z"
        />
        <path
          className="cls-1"
          d="M209.94,195.26V141.67l-21.19-20.31-20.89,20v76.21l9.87,9.87,32.21-32.21Z"
        />
        <path
          className="cls-1"
          d="M281.06,121.36l-21.19,20.31v53.59l32.21,32.21L302,217.6V141.39l-20.89-20Z"
        />
        <path
          className="cls-1"
          d="M256.84,198.29H213L180.75,230.5l9.87,9.87h88.54L289,230.5l-32.21-32.21Z"
        />
        <path
          className="cls-1"
          d="M213,42.08h43.88L289.05,9.87,279.18,0H190.64l-9.87,9.87L213,42.08Z"
        />
      </g>
    </g>
  </svg>
);

export const ResultTypeIcon = (props: {
  resultType: FeatureTypeName;
  fontWeight?: "lighter" | "bold";
  color?: string;
}) => {
  const rs = props.resultType;
  const color = props.color ?? "var(--color_level3)";
  return (
    <div className="custom-badge" style={{ fill: color }} aria-hidden>
      {rs === "boolean" ? (
        <i
          style={{ fontSize: "18px", color: color }}
          className="fa-solid fa-toggle-off"
        ></i>
      ) : rs === "string" ? (
        <StringIcon />
      ) : rs === "number" ? (
        <NumberTypeIcon></NumberTypeIcon>
      ) : (
        <></>
      )}
    </div>
  );
};
