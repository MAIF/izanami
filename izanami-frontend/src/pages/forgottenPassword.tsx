import * as React from "react";
import { NavLink } from "react-router-dom";
import Logo from "../../izanami.png";
import { constraints } from "@maif/react-forms";
import { Form } from "../components/Form";

function resetPassword(email: string): Promise<Response> {
  return fetch("/api/admin/password/_reset", {
    method: "POST",
    body: JSON.stringify({ email: email }),
    headers: {
      "Content-Type": "application/json",
    },
  });
}

export function ForgottenPassword() {
  const [pending, setPending] = React.useState(false);
  const [finished, setFinished] = React.useState(false);
  return finished ? (
    <div className="container">
      <div className="row">
        <div className="d-flex flex-column justify-content-center align-items-center">
          <img
            src={Logo}
            style={{
              marginBottom: 48,
              height: 300,
            }}
          />
          <p>
            If this email is associated with an account, a mail has been send
            with password reset instruction.
            <p className="mt-4">
              <NavLink className={() => "align-self-end"} to={"/login"}>
                Get back to login
              </NavLink>
            </p>
          </p>
        </div>
      </div>
    </div>
  ) : (
    <div className="container">
      <div className="row">
        <div className="col-12">
          <div className="d-flex flex-column justify-content-center align-items-center">
            <img
              src={Logo}
              style={{
                marginBottom: 48,
                height: 300,
              }}
            />
            <h1>Reset password</h1>
            <div style={{ maxWidth: "600px", minWidth: "400px" }}>
              <Form
                schema={{
                  email: {
                    required: true,
                    type: "string",
                    label: "Email",
                    constraints: [
                      constraints.email("Email format is incorrect"),
                    ],
                    props: {
                      autoFocus: true,
                    },
                  },
                }}
                onSubmit={({ email }) => {
                  setPending(true);
                  return resetPassword(email).then(() => {
                    setPending(false);
                    setFinished(true);
                  });
                }}
                footer={({ valid }: { valid: () => void }) => {
                  return (
                    <div className="d-flex justify-content-end pt-3">
                      {pending ? (
                        <div className="spinner-border" role="status">
                          <span className="visually-hidden">Loading...</span>
                        </div>
                      ) : (
                        <>
                          <NavLink to={"/login"}>
                            <button className="btn btn-danger">Cancel</button>
                          </NavLink>
                          <button
                            className="btn btn-success ms-2"
                            onClick={valid}
                          >
                            Reset password
                          </button>
                        </>
                      )}
                    </div>
                  );
                }}
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
