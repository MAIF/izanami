import * as React from "react";
import { useState } from "react";
import { useMutation, useQuery } from "react-query";
import { Link } from "react-router-dom";
import { IzanamiContext } from "../securityContext";
import {
  createPersonnalAccessToken,
  MutationNames,
  personnalAccessTokenKey,
  queryTenants,
  updateUserInformation,
  updateUserPassword,
} from "../utils/queries";
import { constraints } from "@maif/react-forms";
import { Form } from "../components/Form";
import { customStyles } from "../styles/reactSelect";
import { PASSWORD_REGEXP, USERNAME_REGEXP } from "../utils/patterns";
import { Loader } from "../components/Loader";
import queryClient from "../queryClient";
import { TokensTable } from "../components/TokensTable";
import { TokenForm, tokenRightsToObject } from "../components/TokenForm";
import { TokenTenantRight } from "../utils/types";

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
      <hr />
      <h2>Profile details</h2>
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
              <div className="d-flex align-items-center justify-content-between">
                <label>Username : {user.username}</label>
                {!isOIDC && !informationEdition && (
                  <button
                    type="button"
                    className="btn btn-secondary my-2 btn-sm"
                    onClick={() => setInformationEdition(true)}
                  >
                    Update informations
                  </button>
                )}
              </div>
              <div className="d-flex align-items-center justify-content-between mt-1">
                <label className="mt-2">Email: {user.email}</label>
              </div>
            </>
          )}
          <hr />
          <h2 className="mt-4">Rights</h2>
          <label className="mt-2">
            Default tenant:{" "}
            {user.defaultTenant ?? (
              <span style={{ fontStyle: "italic" }}>No default tenant</span>
            )}
          </label>

          <Rights />
          <hr />
          <h2 className="mt-4">Security</h2>

          {!isOIDC && !passwordEdition && (
            <div className="d-flex align-items-center justify-content-between">
              <span>Password</span>

              <button
                type="button"
                className="btn btn-secondary my-2 btn-sm"
                onClick={() => setPasswordEdition(true)}
              >
                Update password
              </button>
            </div>
          )}
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

          {user.admin && <Tokens />}
        </>
      )}
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
        className="sub_container d-flex flex-column"
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
      className="sub_container d-flex flex-column"
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
          <span className="me-1">Izanami instance right: </span>
          <i className="bi bi-shield-check me-1" aria-hidden></i>
          Global Admin
        </div>
      )}
      {Object.entries(tenants || {}).map(([key, value], index, array) => {
        const body = (
          <>
            {Object.entries(value.projects).length > 0 && (
              <>
                <h6 className="mt-3">Projects</h6>
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
                <h6 className="mt-3">Keys</h6>
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
                <h6 className="mt-3">Webhooks</h6>
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
                  You have {value.level} right for the tenant
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
      })}
    </>
  );
}

function Tokens() {
  const { user, displayModal } = React.useContext(IzanamiContext);
  const [creating, setCreating] = useState(false);
  const formTitleRef = React.useRef<HTMLHeadingElement | null>(null);

  const creationQuery = useMutation(
    (data: {
      name: string;
      expiresAt: Date;
      expirationTimezone: string;
      allRights: boolean;
      rights: { [key: string]: TokenTenantRight[] };
    }) =>
      createPersonnalAccessToken(
        user!.username,
        data.name,
        data.expiresAt,
        data.expirationTimezone,
        data.allRights,
        data.rights
      ),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(personnalAccessTokenKey(user!.username!));
      },
    }
  );

  return (
    <>
      <h3
        className="mt-4 d-flex align-items-center"
        style={{ color: "var(--color_level2)" }}
      >
        Personal access tokens&nbsp;
        {!creating && (
          <button
            className="btn btn-secondary btn-sm"
            type="button"
            onClick={() => {
              formTitleRef?.current?.scrollIntoView(true);
              setCreating(true);
            }}
          >
            Create new token
          </button>
        )}
      </h3>
      {creating && (
        <>
          <h4 ref={formTitleRef}>New token</h4>
          <TokenForm
            onSubmit={(token) => {
              return creationQuery
                .mutateAsync({
                  name: token.name,
                  expiresAt: token.expiresAt,
                  expirationTimezone: token.expirationTimezone,
                  allRights: token.allRights,
                  rights: token.allRights
                    ? {}
                    : tokenRightsToObject(token.rights),
                })
                .then((t) =>
                  displayModal(({ close }) => (
                    <OneTimeSecretModalContent
                      message="This is your personnal acess token, make sure to save it somewhere safe."
                      secret={t.token}
                      title="Access token created"
                      onClose={close}
                    />
                  ))
                )
                .then(() => setCreating(false));
            }}
            onCancel={() => setCreating(false)}
          />
        </>
      )}
      {!creating && (
        <p>
          Personal access tokens must be kept secure because there is no way to
          recover a lost token. If you lose a token, you must create a new one.
        </p>
      )}
      <TokensTable user={user!.username} />
    </>
  );
}

type OneTimeSecretState = "Initial" | "Copied" | "Warning";

function OneTimeSecretModalContent(props: {
  message: string;
  secret: string;
  title: string;
  onClose: () => void;
}) {
  const { message, secret, onClose, title } = props;
  const [state, setState] = useState<OneTimeSecretState>("Initial");
  return (
    <>
      <div className="modal-header">
        <h5 className="modal-title">{title}</h5>
      </div>
      <div className="modal-body">
        <label htmlFor="secret">{message}</label>
        <p className="text-warning">It won't be displayed again</p>
        <div className="input-group mb-3">
          <input
            id="secret"
            name="secret"
            type="text"
            className="form-control"
            aria-label="secret"
            value={secret}
            onFocus={() => setState("Copied")}
          />
          <div className="ms-2 input-group-append">
            <button
              className="btn btn-primary"
              type="button"
              onClick={() => {
                navigator.clipboard.writeText(secret);
                setState("Copied");
              }}
            >
              Copy
            </button>
          </div>
        </div>
        {state === "Warning" && (
          <span style={{ color: "#D5443F" }}>
            Please make sure you copied above value before closing this dialog,
            this value won't be displayed again.
          </span>
        )}
      </div>
      <div className="modal-footer">
        <button
          type="button"
          aria-label="Cancel"
          className={"btn btn-danger"}
          data-bs-dismiss="modal"
          onClick={() => {
            if (state === "Initial") {
              setState("Warning");
            } else {
              onClose();
            }
          }}
        >
          Close
        </button>
      </div>
    </>
  );
}
