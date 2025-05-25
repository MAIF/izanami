import * as React from "react";
import { constraints, format, type } from "@maif/react-forms";
import { Form } from "../components/Form";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { GenericTable } from "../components/GenericTable";
import queryClient from "../queryClient";
import { IzanamiContext, useTenantRight } from "../securityContext";
import CodeMirror from "@uiw/react-codemirror";
import Select from "react-select";
import {
  deleteTenant,
  fetchWebhooks,
  importData,
  importIzanamiV1Data,
  inviteUsersToTenant,
  MutationNames,
  pollForImportResult,
  queryKeys,
  queryTenant,
  queryTenantUsers,
  requestExport,
  tenantKeyQueryKey,
  tenantQueryKey,
  tenantUserQueryKey,
  updateTenant,
  updateUserRightsForTenant,
  userQueryKeyForTenant,
  webhookQueryKey,
} from "../utils/queries";
import {
  ImportRequest,
  IzanamiTenantExportRequest,
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
import { TimeZoneSelect } from "../components/TimeZoneSelect";
import { Loader } from "../components/Loader";
import { DEFAULT_TIMEZONE } from "../utils/datetimeUtils";

export function TenantSettings(props: { tenant: string }) {
  const { tenant } = props;
  const { askConfirmation, askInputConfirmation } =
    React.useContext(IzanamiContext);
  const [inviting, setInviting] = React.useState(false);
  const [v1ImportDisplayed, setV1ImportDisplayed] = React.useState(false);
  const [exportDisplayed, setExportDisplayed] = React.useState(false);
  const [importDisplayed, setImportDisplayed] = React.useState(false);
  const tenantQuery = useQuery({
    queryKey: [tenantQueryKey(tenant)],

    queryFn: () => queryTenant(tenant),
  });
  const usersQuery = useQuery({
    queryKey: [tenantUserQueryKey(tenant)],

    queryFn: () => queryTenantUsers(tenant),
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteTenant(props.tenant),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [MutationNames.TENANTS] });
      navigate("/home");
    },
  });

  const inviteUsers = useMutation({
    mutationFn: (data: { users: string[]; level: TLevel }) => {
      const { users, level } = data;
      return inviteUsersToTenant(tenant, users, level);
    },

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [tenantUserQueryKey(tenant)] });
    },
  });

  const navigate = useNavigate();
  const formTitleRef = React.useRef<HTMLHeadingElement | null>(null);
  const [modification, setModification] = React.useState(false);

  if (tenantQuery.isLoading || usersQuery.isLoading) {
    return <Loader message="Loading tenant / tenant users..." />;
  }
  if (tenantQuery.isSuccess && usersQuery.isSuccess) {
    return (
      <>
        <h1>Settings for tenant {tenant}</h1>
        <h2 className="mt-5">
          Tenant users
          <button
            type="button"
            className="btn btn-secondary btn-sm mb-2 ms-3"
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
            cancel={() => setInviting(false)}
            invitedUsers={usersQuery.data?.map((user) => user.username) || []}
          />
        )}
        <TenantUsers tenant={tenant} usersData={usersQuery.data} />
        <hr />
        <h2 className="mt-4">Update tenant information</h2>
        <div className="d-flex align-items-center justify-content-between p-2">
          <span>Update description for this tenant</span>

          <button
            type="button"
            className="btn btn-secondary m-2 btn-sm"
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

        <div className="d-flex align-items-center justify-content-between p-2">
          <span>Delete this tenant</span>
          <button
            className="btn btn-danger m-2 btn-sm"
            onClick={() =>
              askInputConfirmation(
                <>
                  All projects and keys will be deleted. This cannot be undone.
                  <br />
                  Please confirm by typing tenant name below.
                </>,
                async () => {
                  try {
                    await deleteMutation.mutateAsync();
                  } catch (error) {
                    console.error("Error deleting:", error);
                    throw error;
                  }
                },
                tenant,
                `Delete Tenant / ${tenant}`
              )
            }
          >
            Delete Tenant
          </button>
        </div>
        <hr />
        <h2 ref={formTitleRef}>Export / import data</h2>
        {v1ImportDisplayed ? (
          <>
            <h3 className="ms-2 mt-3">Import data from Izanami v1 instance</h3>
            <IzanamiV1ImportForm
              cancel={() => setV1ImportDisplayed(false)}
              submit={(data) => {
                return importIzanamiV1Data({ ...data, tenant })
                  .then((response) => {
                    if (response.status >= 400) {
                      return response
                        .text()
                        .catch(() => {
                          throw new Error(`Failed to import data`);
                        })
                        .then((txt: string) => {
                          throw new Error(
                            `Failed to import data (${response.status}): ${txt}`
                          );
                        });
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
                        {typeof err === "string"
                          ? err
                          : err?.message ?? JSON.stringify(err)}
                      </span>
                    );
                  });
              }}
            />
          </>
        ) : exportDisplayed ? (
          <div>
            <ExportForm
              cancel={() => setExportDisplayed(false)}
              submit={(request: IzanamiTenantExportRequest) =>
                requestExport(tenant, request).then(() =>
                  setExportDisplayed(false)
                )
              }
            />
          </div>
        ) : importDisplayed ? (
          <div>
            <ImportForm
              cancel={() => setImportDisplayed(false)}
              submit={(request) => {
                return importData(tenant, request)
                  .then((importRes) => {
                    if (
                      importRes.conflicts ||
                      importRes?.messages?.length > 0
                    ) {
                      askConfirmation(
                        <>
                          <ImportMessages messages={importRes.messages} />
                          {importRes.conflicts && (
                            <ImportError
                              conflictStrategy={
                                request.conflictStrategy as ConflictStrategy
                              }
                              failedElements={importRes.conflicts}
                            />
                          )}
                        </>,
                        undefined,
                        () => {
                          setImportDisplayed(false);
                          return Promise.resolve();
                        }
                      );
                    } else {
                      setImportDisplayed(false);
                      return Promise.resolve();
                    }
                  })
                  .catch((err) => console.log("err", err));
              }}
            />
          </div>
        ) : (
          <>
            <div className="d-flex align-items-center justify-content-between p-2">
              <span>Import data from V1 Izanami instance</span>

              <button
                type="button"
                className="btn btn-secondary m-2 btn-sm"
                onClick={() => {
                  setV1ImportDisplayed(true);
                  requestAnimationFrame(() => {
                    formTitleRef?.current?.scrollIntoView(true);
                  });
                }}
              >
                Import V1 data
              </button>
            </div>
            <div className="d-flex align-items-center justify-content-between p-2">
              <span>Import data from another Izanami instance</span>
              <button
                type="button"
                className="btn btn-secondary m-2 btn-sm"
                onClick={() => {
                  setImportDisplayed(true);
                  requestAnimationFrame(() => {
                    formTitleRef?.current?.scrollIntoView(true);
                  });
                }}
              >
                Import data
              </button>
            </div>
            <div className="d-flex align-items-center justify-content-between p-2">
              <span>Export data to transfer them to another instance</span>
              <button
                type="button"
                className="btn btn-secondary m-2 btn-sm"
                onClick={() => {
                  setExportDisplayed(true);
                  requestAnimationFrame(() => {
                    formTitleRef?.current?.scrollIntoView(true);
                  });
                }}
              >
                Export data
              </button>
            </div>
          </>
        )}
      </>
    );
  } else {
    return <div>Failed to fetch tenant / tenant users</div>;
  }
}

function ImportMessages(props: { messages: string[] }) {
  return (
    <div>
      <h3>Import messages</h3>
      {props.messages
        .flatMap((msg) => {
          return msg.split("\n");
        })
        .map((m, index) => {
          return (
            <div key={index}>
              {m}
              <br />
            </div>
          );
        })}
    </div>
  );
}

function ImportError(props: {
  conflictStrategy: ConflictStrategy;
  failedElements: object[];
}) {
  const { conflictStrategy, failedElements } = props;
  let explanation;
  if (conflictStrategy === "fail") {
    explanation = (
      <p>
        Some element couldn't be imported.
        <br />
        Since you chose "Fail on conflict" import strategy, nothing has been
        imported.
        <br /> First problematic row is displayed below, see Izanami's log for
        details.
      </p>
    );
  } else {
    explanation = (
      <p>
        Some element couldn't be imported.
        <br />
        Problematic rows are listed below, failure may be caused by either a non
        solvable conflict or a technical error.
        <br />
        See application logs for details.
        <br />
        Non listed rows were imported correctly.
      </p>
    );
  }
  return (
    <div>
      <h3>Import errors !</h3>
      {explanation}
      <CodeMirror
        value={failedElements.map((obj) => JSON.stringify(obj)).join("\n")}
        height="300px"
        readOnly={true}
        theme="dark"
      />
    </div>
  );
}

function ImportForm(props: {
  cancel: () => void;
  submit: (request: ImportRequest) => Promise<any>;
}) {
  const { cancel, submit } = props;

  const methods = useForm<ImportRequest>({
    defaultValues: {},
  });
  const {
    handleSubmit,
    register,
    control,
    formState: { isSubmitting },
  } = methods;

  return (
    <FormProvider {...methods}>
      <form
        className="sub_container d-flex flex-column"
        onSubmit={handleSubmit((data) => submit(data))}
      >
        <h3 className="mt-3">Import data</h3>
        <label className="mt-3">
          On conflict{" "}
          <Tooltip id="import-conflict-tooltip">
            Conflict strategy.
            <ul>
              <li>
                Skip will ignore conflictual elements (they won't be overriden)
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
          Exported file (ndjson)
          <Tooltip id="exported-file">
            Exported ndjson file from another v2 instance
          </Tooltip>
          <input className="form-control" type="file" {...register("file")} />
        </label>
        <div
          className="mt-3 d-flex justify-content-end align-items-center align-self-end"
          style={{
            position: "sticky",
            bottom: "0",
            zIndex: "1",
          }}
        >
          <div
            style={{
              backgroundColor: "var(--bg-color_level2)",
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
}

function TenantUsers(props: { tenant: string; usersData: any }) {
  const { tenant, usersData } = props;

  const userUpdateMutationForTenant = useMutation({
    mutationFn: (user: {
      username: string;
      tenant: string;
      rights: TTenantRight;
    }) => {
      const { username, tenant, rights } = user;
      return updateUserRightsForTenant(username, tenant, rights);
    },

    onSuccess: (_, { username }) => {
      queryClient.invalidateQueries({
        queryKey: [userQueryKeyForTenant(username, tenant)],
      });
    },
  });
  const isTenantAdmin = useTenantRight(tenant, TLevel.Admin);
  return (
    <GenericTable
      data={usersData}
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
                      username: data.username,
                      tenant: tenant,
                      rights: rights,
                    },
                    {
                      onSuccess: () => {
                        cancel();
                        queryClient.invalidateQueries({
                          queryKey: [tenantUserQueryKey(tenant)],
                        });
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
}

function TenantModification(props: { tenant: TenantType; onDone: () => void }) {
  const navigate = useNavigate();

  const updateMutation = useMutation({
    mutationFn: (data: { name: string; description: string }) =>
      updateTenant(props.tenant.name, data),

    onSuccess: (data: any, variables: { name: string; description: string }) =>
      queryClient
        .invalidateQueries({ queryKey: [tenantQueryKey(props.tenant.name)] })
        .then(() => {
          navigate(`/tenants/${variables.name}/settings`);
        }),
  });
  return (
    <div className="sub_container">
      <Form
        schema={{
          name: {
            type: type.string,
            label: "Tenant name",
            defaultValue: props.tenant.name,
            disabled: true,
            required: true,
            props: {
              autoFocus: true,
            },
            constraints: [
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
        onClose={() => props.onDone()}
        submitText="Update"
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

type ConflictStrategy = "skip" | "overwrite" | "fail";

function ExportForm(props: {
  cancel: () => void;
  submit: (request: IzanamiTenantExportRequest) => Promise<any>;
}) {
  const { tenant } = useParams();
  const { cancel, submit } = props;
  const methods = useForm<IzanamiTenantExportRequest>({
    defaultValues: {
      allProjects: true,
      allKeys: true,
      allWebhooks: true,
      userRights: false,
      webhooks: [],
      projects: [],
      keys: [],
    },
  });

  const {
    handleSubmit,
    register,
    getValues,
    watch,
    formState: { isSubmitting },
    control,
  } = methods;

  watch(["allProjects", "allKeys", "allWebhooks"]);

  const projectQuery = useQuery({
    queryKey: [tenantQueryKey(tenant!)],
    queryFn: () => queryTenant(tenant!),
    enabled: !getValues("allProjects"),
  });

  const keyQuery = useQuery({
    queryKey: [tenantKeyQueryKey(tenant!)],
    queryFn: () => queryKeys(tenant!),
    enabled: !getValues("allKeys"),
  });

  const webhookQuery = useQuery({
    queryKey: [webhookQueryKey(tenant!)],
    queryFn: () => fetchWebhooks(tenant!),
    enabled: !getValues("allWebhooks"),
  });

  return (
    <>
      <FormProvider {...methods}>
        <form
          onSubmit={handleSubmit((data) => submit(data))}
          className="sub_container d-flex flex-column"
          style={{
            paddingLeft: "12px",
          }}
        >
          <h3 className="mt-3">Export data</h3>
          <label className="mt-3">
            Export all projects
            <Tooltip id="export-projects-list">
              <div>
                If this is checked, all projects of this tenant will be exported
              </div>
            </Tooltip>
            <input
              type="checkbox"
              className="izanami-checkbox"
              {...register("allProjects")}
            />
          </label>
          {!getValues("allProjects") &&
            (projectQuery.isSuccess ? (
              <label className="mt-3">
                Projects to export
                <Tooltip id="export-projects-list">
                  These projects will be exported
                </Tooltip>
                <Controller
                  name="projects"
                  control={control}
                  render={({ field: { onChange, value } }) => (
                    <Select
                      value={projectQuery!.data.projects
                        ?.map((t) => ({
                          label: t.name,
                          value: t.name,
                        }))
                        .filter((p) => value?.includes(p.value))}
                      onChange={(e) => {
                        onChange(e?.map((v) => v.value));
                      }}
                      options={projectQuery!.data.projects?.map((t) => ({
                        label: t.name,
                        value: t.name,
                      }))}
                      styles={customStyles}
                      isMulti={true}
                      menuPlacement="top"
                    />
                  )}
                />
              </label>
            ) : projectQuery.isError ? (
              <div className="error-message">Failed to fetch projects</div>
            ) : (
              <Loader message="Loading projects" />
            ))}
          <label className="mt-3">
            Export all keys
            <Tooltip id="export-all-keys">
              <div>
                If this is checked, all keys of this tenant will be exported
              </div>
            </Tooltip>
            <input
              type="checkbox"
              className="izanami-checkbox"
              {...register("allKeys")}
            />
          </label>
          {!getValues("allKeys") &&
            (keyQuery.isSuccess ? (
              <label className="mt-3">
                Keys to export
                <Tooltip id="export-key-list">
                  These keys will be exported
                </Tooltip>
                <Controller
                  name="keys"
                  control={control}
                  render={({ field: { onChange, value } }) => (
                    <Select
                      value={keyQuery!.data
                        ?.map((t) => ({
                          label: t.name,
                          value: t.name,
                        }))
                        .filter((p) => value?.includes(p.value))}
                      onChange={(e) => {
                        onChange(e?.map((v) => v.value));
                      }}
                      options={keyQuery!.data?.map((t) => ({
                        label: t.name,
                        value: t.name,
                      }))}
                      styles={customStyles}
                      isMulti={true}
                      menuPlacement="top"
                    />
                  )}
                />
              </label>
            ) : keyQuery.isError ? (
              <div className="error-message">Failed to fetch keys</div>
            ) : (
              <Loader message="Loading keys" />
            ))}
          <label className="mt-3">
            Export all webhooks
            <Tooltip id="export-all-webhooks">
              <div>
                If this is checked, all webhooks of this tenant will be exported
              </div>
            </Tooltip>
            <input
              type="checkbox"
              className="izanami-checkbox"
              {...register("allWebhooks")}
            />
          </label>
          {!getValues("allWebhooks") &&
            (webhookQuery.isSuccess ? (
              <label className="mt-3">
                Webhooks to export
                <Tooltip id="export-key-list">
                  These webhooks will be exported
                </Tooltip>
                <Controller
                  name="webhooks"
                  control={control}
                  render={({ field: { onChange, value } }) => (
                    <Select
                      value={webhookQuery!.data
                        ?.map((t) => ({
                          label: t.name,
                          value: t.name,
                        }))
                        .filter((p) => value?.includes(p.value))}
                      onChange={(e) => {
                        onChange(e?.map((v) => v.value));
                      }}
                      options={webhookQuery!.data?.map((t) => ({
                        label: t.name,
                        value: t.name,
                      }))}
                      styles={customStyles}
                      isMulti={true}
                      menuPlacement="top"
                    />
                  )}
                />
              </label>
            ) : webhookQuery.isError ? (
              <div className="error-message">Failed to fetch webhookQuery</div>
            ) : (
              <Loader message="Loading keys" />
            ))}
          <label className="mt-3">
            Include user rights
            <Tooltip id="include-user-rights">
              <div>
                If this is checked, exported data will contain user rights on
                exported elements.
                <br />
                Including user rights may lead to import errors if users don't
                exist in target instance.
              </div>
            </Tooltip>
            <input
              type="checkbox"
              className="izanami-checkbox"
              {...register("userRights")}
            />
          </label>
          <div
            className="d-flex justify-content-end align-items-center align-self-end"
            style={{
              position: "sticky",
              bottom: "0",
              zIndex: "1",
            }}
          >
            <div
              style={{
                backgroundColor: "var(--bg-color_level2)",
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
                  Export
                </button>
              </>
            )}
          </div>
        </form>
      </FormProvider>
    </>
  );
}

function IzanamiV1ImportForm(props: {
  cancel: () => void;
  submit: (request: IzanamiV1ImportRequest) => Promise<void>;
}) {
  const { tenant } = useParams();

  const projectQuery = useQuery({
    queryKey: [tenantQueryKey(tenant!)],

    queryFn: () => queryTenant(tenant!),
  });
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
                  Whether Izanami should store Wasm script in its own database
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
                Timezone to use for date/time in imported scripts.
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
                      from its name, by splitting it into project / name
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
                    Number of ":" separated fields to keep as project name
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
                  Whether to import feature in a new or an existing project.
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
                        Project to import features into.
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
                backgroundColor: "var(--bg-color_level2)",
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
                <button type="submit" className="btn btn-primary m-2">
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
