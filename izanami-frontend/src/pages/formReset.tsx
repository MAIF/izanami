import * as React from "react";
import { Form, type, format, constraints } from "@maif/react-forms";
import { NavLink } from "react-router-dom";
import { PASSWORD_REGEXP } from "../utils/patterns";

const schema = {
  password: {
    type: type.string,
    format: format.password,
    label: "New password",
    constraints: [
      constraints.required("New passord is required"),
      constraints.matches(
        PASSWORD_REGEXP,
        `Password must match regex ${PASSWORD_REGEXP.toString()}`
      ),
    ],
    props: {
      autoFocus: true,
    },
  },
  repeat: {
    type: type.string,
    format: format.password,
    label: "Repeat new password",
    constraints: [
      constraints.test(
        "password-equality",
        "Passwords must be identical",
        (value, { parent: { password } }) => {
          return password === value;
        }
      ),
    ],
  },
};

export function FormReset(props: { token: string }) {
  const { token } = props;
  const [done, setDone] = React.useState(false);
  return done ? (
    <>
      <div>
        <i
          className="bi bi-check-circle"
          aria-hidden
          style={{ fontSize: "4rem" }}
        ></i>
      </div>
      <div>Password has been successfully updated</div>
      <NavLink className={() => "align-self-end"} to={"/login"}>
        Get back to login
      </NavLink>
    </>
  ) : (
    <Form
      schema={schema}
      onSubmit={({ password }) => {
        fetch("/api/admin/password/_reinitialize", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ password, token }),
        }).then(() => setDone(true));
      }}
    />
  );
}
