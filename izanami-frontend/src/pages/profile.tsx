import * as React from "react";
import { useState } from "react";
import { useMutation, useQuery } from "react-query";
import { Link } from "react-router-dom";
import { IzanamiContext } from "../securityContext";
import {
  MutationNames,
  queryTenants,
  updateUserInformation,
  updateUserPassword,
} from "../utils/queries";
import { constraints } from "@maif/react-forms";
import { Form } from "../components/Form";
import { customStyles } from "../styles/reactSelect";
import { PASSWORD_REGEXP, USERNAME_REGEXP } from "../utils/patterns";
import { Loader } from "../components/Loader";

export function Profile() {
  const ctx = React.useContext(IzanamiContext);
  const user = ctx.user!;
  const isOIDC = user.userType === "OIDC";
  const [informationEdition, setInformationEdition] = useState(false);
  const [passwordEdition, setPasswordEdition] = useState(false);
  const passwordUpdateMutation = useMutation(
    (user: { username: string; oldPassword: string; password: string }) => {
      const { username, ...rest } = user;
      return updateUserPassword(username, rest);
    }
  );
  const informationUpdateMutation = useMutation(
    (user: {
      oldUsername: string;
      username: string;
      email: string;
      password: string;
      defaultTenant?: string;
    }) => {
      const { oldUsername, ...rest } = user;
      return updateUserInformation(oldUsername, rest);
    },
    {
      onSuccess: () => {
        // TODO
      },
    }
  );
  return (
    <div className="anim__popUp">
      <h1>Profile</h1>
      {user.external ? (
        <></>
      ) : (
        <>
          {informationEdition ? (
            <EditionForm
              defaultValues={{
                email: user.email,
                name: user.username,
                defaultTenant: user.defaultTenant,
              }}
              onCancel={() => setInformationEdition(false)}
              onSubmit={(name, email, password, tenant) =>
                informationUpdateMutation
                  .mutateAsync({
                    oldUsername: user.username,
                    username: name,
                    email,
                    password,
                    defaultTenant: tenant,
                  })
                  .then(() => ctx.refreshUser())
                  .then(() => setInformationEdition(false))
              }
            />
          ) : (
            <>
              <label>Username</label>
              <div>{user.username}</div>
              <label className="mt-2">Email</label>
              <div>{user.email}</div>
              <label className="mt-2">Default tenant</label>
              <div>
                {user.defaultTenant ?? (
                  <span style={{ fontStyle: "italic" }}>No default tenant</span>
                )}
              </div>
            </>
          )}
          {!isOIDC && !informationEdition && (
            <button
              type="button"
              className="btn btn-secondary my-2 btn-sm"
              onClick={() => setInformationEdition(true)}
            >
              Update informations
            </button>
          )}
          <br />
          {passwordEdition && (
            <PasswordEditionForm
              onCancel={() => setPasswordEdition(false)}
              onSubmit={(oldPassword, password) =>
                passwordUpdateMutation
                  .mutateAsync({
                    username: user.username,
                    oldPassword,
                    password,
                  })
                  .then(() => setPasswordEdition(false))
              }
            />
          )}

          {!isOIDC && !passwordEdition && (
            <button
              type="button"
              className="btn btn-secondary my-2 btn-sm"
              onClick={() => setPasswordEdition(true)}
            >
              Update password
            </button>
          )}
        </>
      )}

      <h2 className="mt-4">Rights</h2>
      <Rights />
    </div>
  );
}

function EditionForm(props: {
  defaultValues: { name: string; email: string; defaultTenant?: string };
  onCancel: () => void;
  onSubmit: (
    name: string,
    email: string,
    password: string,
    tenant?: string
  ) => Promise<any>;
}) {
  const {
    defaultValues: { name, email, defaultTenant },
    onCancel,
    onSubmit,
  } = props;
  const tenantQuery = useQuery(MutationNames.TENANTS, () => queryTenants());

  if (tenantQuery.error) {
    // FIXME handle error
    return <div>{JSON.stringify(tenantQuery.error)}</div>;
  } else if (tenantQuery.data) {
    return (
      <Form
        schema={{
          name: {
            label: "Username",
            type: "string",
            defaultValue: name,
            required: true,
            constraints: [
              constraints.matches(
                USERNAME_REGEXP,
                `Username must match regex ${USERNAME_REGEXP.toString()}`
              ),
            ],
            props: {
              autoFocus: true,
            },
          },
          email: {
            label: "Email",
            type: "string",
            required: true,
            format: "email",
            defaultValue: email,
            constraints: [constraints.email("Email format is incorrect")],
          },
          defaultTenant: {
            label: "Default tenant",
            type: "string",
            format: "select",
            options: tenantQuery.data.map(({ name }) => ({
              label: name,
              value: name,
            })),
            props: { styles: customStyles },
            defaultValue: defaultTenant,
          },
          password: {
            label: "Your password is required for these modifications",
            required: true,
            type: "string",
            format: "password",
            defaultValue: "",
          },
        }}
        onSubmit={({ name, email, defaultTenant, password }) => {
          return onSubmit(name, email, password, defaultTenant);
        }}
        onClose={() => onCancel()}
        submitText="Update informations"
      />
    );
  } else {
    return <Loader message="Loading tenants..." />;
  }
}

function PasswordEditionForm(props: {
  onCancel: () => void;
  onSubmit: (oldPassword: string, password: string) => Promise<any>;
}) {
  const { onCancel, onSubmit } = props;

  return (
    <Form
      schema={{
        currentPassword: {
          label: "Current password",
          type: "string",
          format: "password",
          props: {
            autoFocus: true,
          },
          required: true,
        },
        newPassword: {
          label: "New password",
          type: "string",
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
          label: "Confirm new password",
          type: "string",
          format: "password",
          constraints: [
            constraints.test(
              "password-equality",
              "Passwords must be identical",
              (value, { parent: { newPassword } }) => {
                return newPassword === value;
              }
            ),
          ],
        },
      }}
      onSubmit={({ currentPassword, newPassword }) =>
        onSubmit(currentPassword, newPassword)
      }
      onClose={() => onCancel()}
      submitText="Update password"
    />
  );
}

function Rights(): JSX.Element {
  const ctx = React.useContext(IzanamiContext);

  const {
    rights: { tenants },
    admin,
  } = ctx.user!;

  return (
    <>
      {admin && (
        <div className="mt-3">
          <h5>
            <i className="bi bi-shield-check me-2" aria-hidden></i>You are
            global admin of this Izanami instance
          </h5>
        </div>
      )}
      {Object.entries(tenants || {}).map(([key, value], index, array) => {
        const body = (
          <>
            {Object.entries(value.projects).length > 0 && (
              <>
                <h4 className="mt-3">Projects</h4>
                {Object.entries(value.projects).map(
                  ([projectName, projectRight]) => {
                    return (
                      <div key={`${key}-${projectName}`}>
                        You have {projectRight.level} right of the project
                        <Link to={`/tenants/${key}/projects/${projectName}`}>
                          <button className="btn btn-sm btn-primary m-1">
                            <i className="fas fa-building" aria-hidden />{" "}
                            {projectName}
                          </button>
                        </Link>{" "}
                      </div>
                    );
                  }
                )}
              </>
            )}

            {Object.entries(value.keys).length > 0 && (
              <>
                <h4 className="mt-3">Keys</h4>
                {Object.entries(value.keys).map(([keyName, keyRight]) => {
                  return (
                    <div key={`${key}-${keyName}`}>
                      You have {keyRight.level} right for the key
                      <Link to={`/tenants/${key}/keys/`}>
                        <button className="btn btn-sm btn-primary m-1">
                          <i className="fas fa-key me-1" aria-hidden />
                          {keyName}
                        </button>
                      </Link>{" "}
                    </div>
                  );
                })}
              </>
            )}

            {Object.entries(value.webhooks).length > 0 && (
              <>
                <h4 className="mt-3">Webhooks</h4>
                {Object.entries(value.webhooks).map(
                  ([webhookName, webhookRight]) => {
                    return (
                      <div key={`${key}-${webhookName}`}>
                        You have {webhookRight.level} right for the webhook
                        <Link to={`/tenants/${key}/webhooks`}>
                          <button className="btn btn-sm btn-primary m-1">
                            <i className="fas fa-plug me-1" aria-hidden />
                            {webhookName}
                          </button>
                        </Link>
                      </div>
                    );
                  }
                )}
              </>
            )}

            {Object.entries(value.keys).length === 0 &&
              Object.entries(value.projects).length === 0 &&
              Object.entries(value.webhooks).length === 0 && (
                <div>You have no specific rights inside this tenant</div>
              )}
          </>
        );

        if (array.length === 1) {
          return (
            <>
              <h3 className="mt-3">Tenant&nbsp;</h3>
              You have {value.level} right for the tenant
              <Link to={`/tenants/${key}`}>
                <button className="btn btn-sm btn-primary m-1">
                  <i className="fas fa-cloud me-1" aria-hidden />
                  {key}
                </button>
              </Link>
              <div className="ms-4">{body}</div>
            </>
          );
        } else {
          return (
            <div className="accordion mt-3" id={`${key}-accordion`} key={key}>
              <div className="accordion-item">
                <h3 className="accordion-header">
                  <button
                    className="accordion-button"
                    type="button"
                    data-bs-toggle="collapse"
                    data-bs-target={`#${key}-accordion-collapse`}
                    aria-expanded="true"
                    aria-controls={`${key}-accordion-collapse`}
                  >
                    You are {value.level} for the tenant
                    <Link to={`/tenants/${key}`}>
                      <button className="btn btn-sm btn-primary m-1">
                        <i className="fas fa-cloud me-1" aria-hidden />
                        {key}
                      </button>
                    </Link>
                  </button>
                </h3>
                <div
                  className="accordion-collapse collapse"
                  aria-labelledby="headingOne"
                  data-bs-parent={`#${key}-accordion`}
                  id={`${key}-accordion-collapse`}
                >
                  <div className="accordion-body">{body}</div>
                </div>
              </div>
            </div>
          );
        }
      })}
    </>
  );
}
