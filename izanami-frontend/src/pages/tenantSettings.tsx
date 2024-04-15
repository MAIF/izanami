import { Form, constraints, format, type } from "@maif/react-forms";
import * as React from "react";
import { useMutation, useQuery } from "react-query";
import { useNavigate, useParams } from "react-router-dom";
import { GenericTable } from "../components/GenericTable";
import queryClient from "../queryClient";
import { IzanamiContext, useTenantRight } from "../securityContext";
import Select from "react-select";
import {
  deleteTenant,
  importIzanamiV1Data,
  inviteUsersToTenant,
  MutationNames,
  pollForImportResult,
  queryTenant,
  queryTenantUsers,
  tenantQueryKey,
  tenantUserQueryKey,
  updateTenant,
  updateUserRightsForTenant,
  userQueryKeyForTenant,
} from "../utils/queries";
import {
  IzanamiV1ImportRequest,
  TenantType,
  TLevel,
  TTenantRight,
  TUser,
} from "../utils/types";
import { UserEdition } from "./users";
import { InvitationForm } from "../components/InvitationForm";
import { TENANT_NAME_REGEXP } from "../utils/patterns";
import { Controller, FormProvider, useForm } from "react-hook-form";
import { Tooltip } from "../components/Tooltip";
import { customStyles } from "../styles/reactSelect";
import { DEFAULT_TIMEZONE, TimeZoneSelect } from "../components/TimeZoneSelect";
import { Loader } from "../components/Loader";

const loadOptions = (
  inputValue: string,
  callback: (options: string[]) => void
) => {
  fetch(`/api/admin/users/search?query=${inputValue}&count=20`)
    .then((resp) => resp.json())
    .then((data) =>
      callback(data.map((d: string) => ({ label: d, value: d })))
    );
};

export function TenantSettings(props: { tenant: string }) {
  const { tenant } = props;
  const [inviting, setInviting] = React.useState(false);
  const [v1ImportDisplayed, setV1ImportDisplayed] = React.useState(false);

  const tenantQuery = useQuery(tenantQueryKey(tenant), () =>
    queryTenant(tenant)
  );

  const deleteMutation = useMutation(() => deleteTenant(tenant), {
    onSuccess: () => {
      queryClient.invalidateQueries(MutationNames.TENANTS);
    },
  });

  const inviteUsers = useMutation(
    (data: { users: string[]; level: TLevel }) => {
      const { users, level } = data;
      return inviteUsersToTenant(tenant, users, level);
    },
    {
      onSuccess: () => {
        queryClient.invalidateQueries(tenantUserQueryKey(tenant));
      },
    }
  );

  const { askConfirmation } = React.useContext(IzanamiContext);
  const navigate = useNavigate();

  const [modification, setModification] = React.useState(false);

  if (tenantQuery.isLoading) {
    return (
      <>
        <h1>Settings</h1>
        <Loader message="Loading..." />
      </>
    );
  } else if (tenantQuery.data) {
    return (
      <>
        <h1>Settings for tenant {tenant}</h1>
        {tenantQuery.data.description && (
          <div>{tenantQuery.data.description}</div>
        )}
        <h2 className="mt-5">
          Tenant users
          <button
            type="button"
            className="btn btn-primary btn-sm mb-2 ms-3"
            onClick={() => setInviting(true)}
          >
            Invite new users
          </button>
        </h2>
        {inviting && (
          <InvitationForm
            submit={({ users, level }) =>
              inviteUsers
                .mutateAsync({ users, level })
                .then(() => setInviting(false))
            }
            loadOptions={loadOptions as any} // FIXME TS
            cancel={() => setInviting(false)}
          />
        )}
        <TenantUsers tenant={tenant} />
        <hr />
        <h2 className="mt-4">Danger zone</h2>
        <div className="border border-danger rounded p-2 mt-3">
          <div className="d-flex align-items-center justify-content-between p-2">
            <span>Update the description of the tenant</span>

            <button
              type="button"
              className="btn btn-danger m-2 btn-sm"
              onClick={() => {
                setModification(true);
              }}
            >
              Update tenant
            </button>
          </div>

          {modification && (
            <TenantModification
              tenant={tenantQuery.data}
              onDone={() => setModification(false)}
            />
          )}
          <hr />
          <div className="d-flex align-items-center justify-content-between p-2">
            <span>Delete this tenant</span>
            <button
              type="button"
              className="btn btn-danger m-2 btn-sm"
              onClick={() =>
                askConfirmation(
                  <>
                    Are you sure you wan't to delete tenant {props.tenant} ?
                    <br />
                    All projects and keys will be deleted, this cannot be
                    undone.
                  </>,
                  () =>
                    deleteMutation.mutateAsync().then(() => navigate("/home"))
                )
              }
            >
              Delete tenant
            </button>
          </div>
        </div>
        <hr />
        <h2>Import data from V1</h2>
        {!v1ImportDisplayed ? (
          <button
            className="btn btn-primary"
            type="button"
            onClick={() => setV1ImportDisplayed(true)}
          >
            Import V1 data
          </button>
        ) : (
          <>
            <IzanamiV1ImportForm
              cancel={() => setV1ImportDisplayed(false)}
              submit={(data) => {
                return importIzanamiV1Data({ ...data, tenant })
                  .then((response) => {
                    if (response.status >= 400) {
                      // TODO handle body ?
                      throw new Error(
                        `Failed to import data (${response.status})`
                      );
                    } else {
                      return response.json().then((body: any) => body.id);
                    }
                  })
                  .then((id) => pollForImportResult(tenant, id))
                  .then((res) => {
                    if (
                      !res.incompatibleScripts ||
                      res.incompatibleScripts.length === 0
                    ) {
                      askConfirmation("Import succeeded !");
                    } else {
                      askConfirmation(
                        <div>
                          <h1>Import succeeded with warning !</h1>
                          Below scripts could not be imported.
                          <br />
                          <ul>
                            {res.incompatibleScripts?.map((s) => (
                              <li key={s}>{s}</li>
                            ))}
                          </ul>
                          Only Javascript scripts that don't use http client are
                          supported.
                          <br />
                          Associated features were imported as basic disabled
                          features.
                        </div>
                      );
                    }
                  })
                  .then(() => setV1ImportDisplayed(false))
                  .catch((err) => {
                    askConfirmation(
                      <span className="error-message">
                        An error occured :{" "}
                        {typeof err === "string" ? err : JSON.stringify(err)}
                      </span>
                    );
                  });
              }}
            />
          </>
        )}
      </>
    );
  } else {
    return <div>Failed to fetch tenant query</div>;
  }
}

function TenantUsers(props: { tenant: string }) {
  const { tenant } = props;
  const userQuery = useQuery(tenantUserQueryKey(tenant), () =>
    queryTenantUsers(tenant)
  );
  const userUpdateMutationForTenant = useMutation(
    (user: { username: string; tenant: string; rights: TTenantRight }) => {
      const { username, tenant, rights } = user;
      return updateUserRightsForTenant(username, tenant, rights);
    },
    {
      onSuccess: (_, { username }) => {
        queryClient.invalidateQueries(userQueryKeyForTenant(username, tenant));
      },
    }
  );
  const isTenantAdmin = useTenantRight(tenant, TLevel.Admin);
  if (userQuery.isLoading) {
    return <Loader message="Loading users..." />;
  } else if (userQuery.data) {
    return (
      <GenericTable
        data={userQuery.data}
        customRowActions={{
          edit: {
            icon: (
              <>
                <i className="bi bi-pencil-square" aria-hidden></i> Edit
              </>
            ),
            hasRight: (currentUser, { username }) =>
              (isTenantAdmin || false) && currentUser.username !== username,
            customForm(data, cancel) {
              return (
                <UserEdition
                  submit={(created: TUser) => {
                    const rights = created?.rights?.tenants?.[tenant] as any;
                    return userUpdateMutationForTenant.mutateAsync(
                      {
                        username: created.username,
                        tenant: tenant,
                        rights: rights,
                      },
                      {
                        onSuccess: () => {
                          cancel();
                          queryClient.invalidateQueries(
                            tenantUserQueryKey(tenant)
                          );
                        },
                      }
                    );
                  }}
                  cancel={cancel}
                  username={data.username}
                  tenant={tenant}
                />
              );
            },
          },
        }}
        columns={[
          {
            accessorKey: "username",
            header: () => "Username",
            size: 15,
          },
          {
            accessorKey: "admin",
            header: () => "Admin",
            meta: {
              valueType: "boolean",
            },
            size: 10,
          },
          {
            accessorKey: "userType",
            header: () => "User type",
            meta: {
              valueType: "discrete",
            },
            size: 15,
          },
          {
            accessorKey: "right",
            header: () => "Right level",
            size: 15,
          },
        ]}
        idAccessor={(u) => u.username}
      />
    );
  } else {
    return <div>Failed to load tenant users</div>;
  }
}

function TenantModification(props: { tenant: TenantType; onDone: () => void }) {
  const navigate = useNavigate();

  const updateMutation = useMutation(
    (data: { name: string; description: string }) =>
      updateTenant(props.tenant.name, data),
    {
      onSuccess: (
        data: any,
        variables: { name: string; description: string }
      ) =>
        queryClient
          .invalidateQueries(tenantQueryKey(props.tenant.name))
          .then(() => {
            navigate(`/tenants/${variables.name}/settings`);
          }),
    }
  );
  return (
    <div className="sub_container">
      <Form
        schema={{
          name: {
            type: type.string,
            label: "Tenant name",
            defaultValue: props.tenant.name,
            disabled: true,
            props: {
              autoFocus: true,
            },
            constraints: [
              constraints.required("Tenant name is required"),
              constraints.matches(
                TENANT_NAME_REGEXP,
                `Password must match regex ${TENANT_NAME_REGEXP.toString()}`
              ),
            ],
          },
          description: {
            type: type.string,
            format: format.textarea,
            label: "Description",
            defaultValue: props.tenant.description,
          },
        }}
        onSubmit={(data: any) =>
          updateMutation.mutateAsync(data).then(() => props.onDone())
        }
        footer={({ valid }: { valid: () => void }) => {
          return (
            <>
              <div className="d-flex justify-content-end">
                <button
                  type="button"
                  className="btn btn-danger m-2"
                  onClick={() => props.onDone()}
                >
                  Cancel
                </button>
                <button className="btn btn-success m-2" onClick={valid}>
                  Update
                </button>
              </div>
            </>
          );
        }}
      />
    </div>
  );
}

const CONFLICT_STRATEGIES_OPTIONS = [
  { label: "skip", value: "skip" },
  {
    label: "overwrite",
    value: "overwrite",
  },
  { label: "fail", value: "fail" },
];

function IzanamiV1ImportForm(props: {
  cancel: () => void;
  submit: (request: IzanamiV1ImportRequest) => Promise<void>;
}) {
  const { tenant } = useParams();

  const projectQuery = useQuery(tenantQueryKey(tenant!), () =>
    queryTenant(tenant!)
  );
  const { cancel, submit } = props;

  const methods = useForm<IzanamiV1ImportRequest>({
    defaultValues: {},
  });
  const {
    handleSubmit,
    register,
    control,
    getValues,
    setValue,
    watch,
    formState: { isSubmitting },
  } = methods;
  const { onChange, ...rest } = register("newProject");

  watch(["newProject", "deduceProject", "zone"]);

  if (projectQuery.error) {
    return <div>Failed to load tenant list</div>;
  } else if (projectQuery.data) {
    return (
      <FormProvider {...methods}>
        <form
          onSubmit={handleSubmit((data) => submit(data))}
          className="d-flex flex-column flex-shrink-1 fieldset-form"
        >
          <fieldset>
            <legend>General</legend>
            <label>
              On conflict{" "}
              <Tooltip id="conflict-tooltip">
                Conflict strategy.
                <ul>
                  <li>
                    Skip will ignore conflictual elements (they won't be
                    inserted)
                  </li>
                  <li>Fail will fail on any conflict (except on projects)</li>
                  <li>Overwrite will write imported version over existing</li>
                </ul>
              </Tooltip>
              <Controller
                name="conflictStrategy"
                defaultValue="skip"
                control={control}
                render={({ field: { onChange, value } }) => (
                  <Select
                    value={CONFLICT_STRATEGIES_OPTIONS.find(
                      ({ value: aValue }) => value === aValue
                    )}
                    onChange={(value) => {
                      onChange(value?.value);
                    }}
                    styles={customStyles}
                    options={CONFLICT_STRATEGIES_OPTIONS}
                  />
                )}
              />
            </label>
            <label className="mt-3">
              Inline WASM scripts as Base64
              <Tooltip id="inline-scripts">
                <div>
                  Wether Izanami should store wasm script in its own database
                </div>
                <div>(inBase64 format) or rely on Wasmo</div>
                <div>to provide scripts at runtime. </div>
                <div>Either way, you MUST have a Wasmo instance</div>
                <div>configured to import feature with script.</div>
              </Tooltip>
              <input
                type="checkbox"
                className="izanami-checkbox"
                defaultChecked={false}
                {...register("inlineScript")}
              />
            </label>
          </fieldset>
          <fieldset>
            <legend>Features</legend>
            <label>
              Exported feature files (ndjson)
              <input
                className="form-control"
                type="file"
                {...register("featureFiles")}
              />
            </label>

            <label className="mt-3">
              Timezone
              <Tooltip id="import-timezone">
                Timezine to use for date/time in imported scripts.
              </Tooltip>
              <Controller
                name="zone"
                defaultValue={DEFAULT_TIMEZONE}
                control={control}
                render={({ field: { onChange, value } }) => (
                  <TimeZoneSelect onChange={onChange} value={value} />
                )}
              />
            </label>

            <>
              <>
                <label className="mt-3">
                  Deduce project from feature name
                  <Tooltip id="import-deduce-project">
                    <div>
                      If this is checked, Izanami will try to deduce feature
                      project
                    </div>
                    <div>
                      from its name,by splitting it into project / name
                      combination.
                    </div>
                  </Tooltip>
                  <input
                    type="checkbox"
                    className="izanami-checkbox"
                    defaultChecked={false}
                    {...register("deduceProject")}
                  />
                </label>
                {getValues("deduceProject") && (
                  <label>
                    Number of ":" seprarated fields to keep as project name
                    <Tooltip id="project-name-size">
                      <div>When deducing project name from feature id,</div>
                      <div>this will indicate how many sections should</div>
                      <div>be use to represent the project.</div>
                      <div>For instance, a feature "my:super:feature"</div>
                      <div>will be read as owned by project "my:super"</div>
                      <div>if this field is valued to 2.</div>
                    </Tooltip>
                    <input
                      className="form-control"
                      type="number"
                      defaultValue={1}
                      {...register("projectPartSize")}
                    />
                  </label>
                )}
              </>
              <label
                className="mt-3"
                style={{
                  display: !getValues("deduceProject") ? "" : "none",
                }}
              >
                Import in a new project
                <Tooltip id="import-in-new-project">
                  Wether to import feature in a new or an existing project
                </Tooltip>
                <input
                  type="checkbox"
                  className="izanami-checkbox"
                  defaultChecked={true}
                  onChange={(e) => {
                    setValue("project", "");
                    onChange(e);
                  }}
                  {...rest}
                />
              </label>
              {projectQuery.data &&
                !getValues("newProject") &&
                !getValues("deduceProject") && (
                  <>
                    <label className="mt-3">
                      Target project
                      <Tooltip id="target-existing-project">
                        Project to import features into
                      </Tooltip>
                      <Controller
                        name="project"
                        control={control}
                        render={({ field: { onChange, value } }) => (
                          <Select
                            value={value ? { label: value, value } : undefined}
                            onChange={(e) => {
                              onChange(e?.value);
                            }}
                            options={projectQuery.data.projects!.map((t) => ({
                              label: t.name,
                              value: t.name,
                            }))}
                            styles={customStyles}
                          />
                        )}
                      />
                    </label>
                  </>
                )}
              {getValues("newProject") && !getValues("deduceProject") && (
                <label className="mt-3">
                  New project name
                  <input
                    type="text"
                    className="form-control"
                    {...register("project")}
                  />
                </label>
              )}
            </>
          </fieldset>
          <fieldset>
            <legend>Users</legend>
            <label>
              Exported user files (ndjson)
              <input
                className="form-control"
                type="file"
                {...register("userFiles")}
              />
            </label>
          </fieldset>
          <fieldset>
            <legend>Keys</legend>
            <label>
              Exported key files (ndjson)
              <input
                className="form-control"
                type="file"
                {...register("keyFiles")}
              />
            </label>
          </fieldset>
          <fieldset>
            <legend>Scripts</legend>
            <label>
              Exported script files (ndjson)
              <input
                className="form-control"
                type="file"
                {...register("scriptFiles")}
              />
            </label>
          </fieldset>
          <div
            className="d-flex justify-content-end align-items-center"
            style={{
              position: "sticky",
              bottom: "0",
              zIndex: "1",
            }}
          >
            <div
              style={{
                backgroundColor:"var(--bg-color_level2)",
                width: "185px",
                height: "55px",
                position: "absolute",
                top: "0",
                right: "0",
                zIndex: "-1",
                borderRadius: "10px",
              }}
            ></div>
            <button
              type="button"
              className="btn btn-danger m-2"
              onClick={() => cancel()}
            >
              Cancel
            </button>
            {isSubmitting ? (
              <div className="spinner-border" role="status">
                <span className="visually-hidden">Loading...</span>
              </div>
            ) : (
              <>
                <button type="submit" className="btn btn-success m-2">
                  Import
                </button>
              </>
            )}
          </div>
        </form>
      </FormProvider>
    );
  } else {
    return <Loader message="Loading..." />;
  }
}
