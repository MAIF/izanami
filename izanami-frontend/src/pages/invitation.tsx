import React from "react";
import { useMutation } from "react-query";
import Logo from "../../izanami.png";
import { createUser } from "../utils/queries";
import { constraints } from "@maif/react-forms";
import { Form } from "../components/Form";

import { PASSWORD_REGEXP, USERNAME_REGEXP } from "../utils/patterns";

export function Invitation(props: { token: string }) {
  const { token } = props;

  const userCreationMutation = useMutation(
    (data: { username: string; password: string; token: string }) => {
      const { username, password, token } = data;
      return createUser(username, password, token);
    }
  );

  return (
    <>
      <div className="container">
        <div className="row">
          <div className="col-4 offset-4 d-flex align-items-center flex-column">
            {" "}
            <img
              src={Logo}
              className="img-fluid"
              style={{
                width: 200,
              }}
            />
            <h1 className="mt-5">Create your account</h1>
            <Form
              schema={{
                username: {
                  type: "string",
                  label: "Username",
                  required: true,
                  constraints: [
                    constraints.matches(
                      USERNAME_REGEXP,
                      `Username must match regex ${USERNAME_REGEXP.toString()}`
                    ),
                  ],
                },
                password: {
                  type: "string",
                  label: "Password",
                  format: "password",
                  required: true,
                  constraints: [
                    constraints.matches(
                      PASSWORD_REGEXP,
                      `Password must match regex ${PASSWORD_REGEXP.toString()}`
                    ),
                  ],
                },
                confirmPassword: {
                  type: "string",
                  label: "Confirm password",
                  format: "password",
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
              }}
              submitText="Create user"
              onSubmit={function (data: { [x: string]: any }): Promise<any> {
                const { username, password } = data;
                return userCreationMutation
                  .mutateAsync({ username, password, token })
                  .then(() => {
                    location.search = "";
                    location.href = "/login";
                  });
              }}
            />
          </div>
        </div>
      </div>
    </>
  );
}
