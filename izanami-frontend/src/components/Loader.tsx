import * as React from "react";

export function Loader(props: { message?: string }) {
  return (
    <>
      <div className="d-flex justify-content-center">
        <i className="fas fa-cog fa-spin" style={{ fontSize: "30px" }} />
      </div>
      {props.message && (
        <h5 style={{ textAlign: "center" }} className="mt-3">
          {props.message}
        </h5>
      )}
    </>
  );
}
