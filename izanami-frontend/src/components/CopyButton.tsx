import * as React from "react";
import { Tooltip } from "react-tooltip";

export function CopyButton(props: {
  value: string;
  icon?: boolean;
  secondary?: boolean;
}) {
  const [validCheckMark, setValidCheckmark] = React.useState(false);
  const timeRef = React.useRef<undefined | NodeJS.Timeout>(undefined);

  return (
    <button
      className={`ms-2 btn btn-${props.secondary ? "secondary" : "primary"}`}
      type="button"
      onClick={() => {
        navigator.clipboard.writeText(props.value);
        if (timeRef.current) {
          clearTimeout(timeRef.current);
        }
        setValidCheckmark(true);
        timeRef.current = setTimeout(() => {
          setValidCheckmark(false);
        }, 3000);
      }}
    >
      {validCheckMark ? (
        <>
          <i
            className="bi bi-check-circle-fill"
            data-tooltip-id="copy"
            data-tooltip-content="Copied to clipboard !"
            data-tooltip-place="top"
          ></i>
          <Tooltip id="copy" isOpen={validCheckMark} />
        </>
      ) : (
        <>
          {props.icon ? (
            <i className="bi bi-clipboard" aria-label="copy"></i>
          ) : (
            "Copy"
          )}
        </>
      )}
    </button>
  );
}
