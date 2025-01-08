import * as React from "react";
import { ErrorMessage as BaseErrorMessage } from "@hookform/error-message";

export function ErrorMessage(props: { errors: object; name: string }) {
  return (
    <BaseErrorMessage
      errors={props.errors}
      name={props.name}
      render={({ message }) => {
        return (
          <div className="error-message">
            <i className="fa-solid fa-circle-exclamation" aria-hidden />
            {message ? <>&nbsp;{message}</> : <></>}
          </div>
        );
      }}
    />
  );
}
