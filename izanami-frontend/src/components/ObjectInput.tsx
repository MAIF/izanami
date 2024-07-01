import * as React from "react";

export function ObjectInput(props: {
  value: { name: string; value: string }[];
  onChange: (newValue: { name: string; value: string }[]) => void;
  params?: {
    nameLabel?: string;
    valueLabel?: string;
    addLabel?: string;
    smallerFirstCol?: boolean;
  };
}) {
  const arr = props.value;

  const {
    nameLabel = "Header name",
    valueLabel = "Header value",
    addLabel = "Add header",
    smallerFirstCol = true,
  } = props.params || {};
  return (
    <div className="container-fluid row-gap-3">
      {arr.length > 0 && (
        <div className="row my-1">
          <label
            className={smallerFirstCol ? `col-3 col-md4` : `col-4 col-md-5`}
          >
            {nameLabel}
          </label>
          <label
            className={smallerFirstCol ? `col-5 col-md-6` : `col-4 col-md-5`}
          >
            {valueLabel}
          </label>
        </div>
      )}
      {arr.map(({ name, value }, index) => (
        <div className="row my-1" key={`header-${index}`}>
          <div className={smallerFirstCol ? `col-3 col-md4` : `col-4 col-md-5`}>
            <input
              aria-label={`header-${index}-name`}
              className="form-control"
              value={name}
              onChange={(e) => {
                const v = e.target.value;
                props.onChange([
                  ...arr
                    .slice(0, index)
                    .concat([{ name: v, value }])
                    .concat(arr.slice(index + 1)),
                ]);
              }}
            />
          </div>
          <div
            className={smallerFirstCol ? `col-5 col-md-6` : `col-4 col-md-5`}
          >
            <input
              className="form-control"
              aria-label={`header-${index}-value`}
              value={value}
              onChange={(e) => {
                const v = e.target.value;
                props.onChange([
                  ...arr
                    .slice(0, index)
                    .concat([{ name, value: v }])
                    .concat(arr.slice(index + 1)),
                ]);
              }}
            />
          </div>
          <div className="col-4 col-md-2 d-flex justify-content-start">
            <button
              className="btn btn-danger"
              type="button"
              onClick={() => {
                props.onChange([
                  ...arr.slice(0, index).concat(arr.slice(index + 1)),
                ]);
              }}
            >
              Delete
            </button>
          </div>
        </div>
      ))}
      <div className="row my-1">
        <div className="col-8 col-md-9"></div>
        <div className="col-4 col-md-2 d-flex justify-content-start">
          <button
            className="btn btn-secondary"
            type="button"
            onClick={() => {
              props.onChange([...arr, { name: "", value: "" }]);
            }}
          >
            {addLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
