import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ColumnDef, Row } from "@tanstack/react-table";
import {
  createKey,
  deleteKey,
  fetchKeyUsers,
  keyUserQueryKey,
  queryKeys,
  tenantKeyQueryKey,
  updateKey,
  updateKeyRightsFor,
} from "../utils/queries";
import { ProjectType, TKey, TLevel } from "../utils/types";
import queryClient from "../queryClient";
import { Form } from "../components/Form";
import { constraints, format, type } from "@maif/react-forms";
import { GenericTable } from "../components/GenericTable";
import { customStyles } from "../styles/reactSelect";
import { Modal } from "../components/Modal";
import {
  hasRightForKey,
  IzanamiContext,
  useTenantRight,
} from "../securityContext";
import { KEY_NAME_REGEXP } from "../utils/patterns";
import { Loader } from "../components/Loader";
import { useSearchParams } from "react-router-dom";
import { InvitationForm } from "../components/InvitationForm";
import { RightTable } from "../components/RightTable";
import { Tooltip } from "../components/Tooltip";
import { ProjectLink } from "../components/ProjectLink";
import { CopyButton } from "../components/CopyButton";

function editionSchema(tenant: string, key?: TKey) {
  return {
    name: {
      label: "Name",
      type: type.string,
      defaultValue: key?.name || "",
      required: true,
      props: {
        autoFocus: true,
      },
      constraints: [
        constraints.matches(
          KEY_NAME_REGEXP,
          `Key name must match regex ${KEY_NAME_REGEXP.toString()}`
        ),
      ],
    },
    enabled: {
      label: () => (
        <>
          Enabled
          <Tooltip id="enabled-key-id">
            Key must be enabled to call Izanami endpoints
          </Tooltip>
        </>
      ),
      type: type.bool,
      defaultValue: key?.enabled ?? false,
      props: {
        "aria-label": "Enabled",
        id: "enabled",
      },
    },
    admin: {
      label: () => (
        <>
          Tenant wide
          <Tooltip id="unscoped-key-id">
            A tenant wide key can read all tenant features.
          </Tooltip>
        </>
      ),
      type: type.bool,
      defaultValue: key?.admin ?? false,
    },
    projects: {
      label: () => (
        <>
          Allowed projects
          <Tooltip id="allowed-project-key-id">
            Non tenant wide keys can access only features of specified projects.
          </Tooltip>
        </>
      ),
      type: type.string,
      format: format.select,
      isMulti: true,
      optionsFrom: `/api/admin/tenants/${tenant}/projects`,
      props: { "aria-label": "projects", styles: customStyles },
      transformer: (project: ProjectType) => {
        return { label: project.name, value: project.name };
      },
      defaultValue: key?.projects || undefined,
      visible: ({ rawValues }: { rawValues: any }) => !rawValues.admin,
    },
    description: {
      label: "Description",
      type: type.string,
      format: format.textarea,
      defaultValue: key?.description || "",
    },
  };
}

export default function Keys(props: { tenant: string }) {
  const [searchParams] = useSearchParams();
  const selectedSearchRow = searchParams.get("filter");
  const { tenant } = props;
  const [secret, setSecret] = React.useState<string | undefined>(undefined);
  const [clientid, setClientId] = React.useState<string | undefined>(undefined);
  const keyQuery = useQuery({
    queryKey: [tenantKeyQueryKey(tenant)],

    queryFn: () => queryKeys(tenant),
  });
  const hasTenantWriteRight = useTenantRight(tenant, TLevel.Write);
  const [creating, setCreating] = React.useState(false);
  const { refreshUser, askInputConfirmation } =
    React.useContext(IzanamiContext);

  const keyDeleteMutation = useMutation({
    mutationKey: [tenantKeyQueryKey(tenant)],

    mutationFn: (params: { name: string }) => deleteKey(tenant, params.name),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [tenantKeyQueryKey(tenant)] });
      refreshUser();
    },
  });
  const keyUpdateMutation = useMutation({
    mutationKey: [tenantKeyQueryKey(tenant)],

    mutationFn: (params: { oldName: string; newKey: TKey }) =>
      updateKey(tenant, params.oldName, params.newKey),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [tenantKeyQueryKey(tenant)] });
    },
  });
  const keyCreateMutation = useMutation({
    mutationKey: [tenantKeyQueryKey(tenant)],

    mutationFn: (params: { key: TKey; tenant: string }) =>
      createKey(params.tenant, params.key),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [tenantKeyQueryKey(tenant)] });
    },
  });

  const columns: ColumnDef<TKey>[] = [
    {
      accessorKey: "name",
      header: () => "Key name",
      minSize: 150,
      size: 15,
    },
    {
      accessorKey: "enabled",
      cell: (info: any) =>
        info.getValue() ? (
          <span className="activation-status">Enabled</span>
        ) : (
          <span className="activation-status disabled-status">Disabled</span>
        ),
      header: () => "Status",
      minSize: 150,
      size: 10,
      meta: {
        valueType: "status",
      },
    },
    {
      accessorKey: "clientId",
      header: () => "Client ID",
      cell: (info: any) => {
        return (
          <div
            style={{
              position: "relative",
            }}
          >
            <div
              style={{
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
                overflow: "hidden",
                maxWidth: "110px",
              }}
            >
              {info.getValue()}
            </div>
            <div
              style={{
                position: "absolute",
                right: 0,
                top: "50%",
                transform: "translateY(-50%)",
              }}
            >
              <CopyButton icon secondary value={info.getValue()} />
            </div>
          </div>
        );
      },
      minSize: 100,
      size: 15,
    },
    {
      id: "clientSecret",
      header: () => "Secret",
      minSize: 100,
      size: 15,
      cell: (info: any) => {
        return (
          <div
            className="copiable-cell"
            style={{
              position: "relative",
            }}
          >
            <div
              style={{
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
                overflow: "hidden",
                maxWidth: "75px",
              }}
            >
              {info.row.original.clientSecret}
            </div>
            <div
              style={{
                position: "absolute",
                right: 0,
                top: "50%",
                transform: "translateY(-50%)",
              }}
            >
              <CopyButton icon secondary value={info.getValue()} />
            </div>
          </div>
        );
      },
    },
    {
      accessorKey: "projects",
      header: () => "Scope",
      minSize: 200,
      size: 10,
      meta: {
        valueType: "discrete",
      },
      filterFn: (row: Row<TKey>, columnId: string, filterValue: any) => {
        if (!filterValue || filterValue?.length === 0) {
          return true;
        }
        const value: any = row.getValue(columnId);
        const isTenantWide = row.original.admin;

        return filterValue.some(
          (v: string) => value.includes(v) || isTenantWide
        );
      },
      cell: (info: any) => {
        if (info.row.original.admin) {
          return (
            <span className="fw-bold fst-italic">
              {" "}
              <i className="fas fa-cloud" aria-hidden></i>&nbsp;Tenant wide
            </span>
          );
        } else {
          return info.row.original.projects.map((p: string) => {
            return (
              <>
                <ProjectLink
                  key={p}
                  tenant={tenant}
                  project={p}
                  className="table-link"
                />
                <br />
              </>
            );
          });
        }
      },
    },
    {
      id: "description",
      header: () => "Description",
      minSize: 150,
      size: 35,
      cell: (info: any) => {
        return (
          <div
            style={{
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
              overflow: "hidden",
              maxWidth: "150px",
            }}
          >
            {info.row.original.description}
          </div>
        );
      },
    },
  ];

  if (keyQuery.error) {
    return <div>Error while fetching keys</div>;
  } else if (keyQuery.data) {
    return (
      <>
        <div className="d-flex align-items-center">
          <h1>Client keys</h1>
          {hasTenantWriteRight && !creating && keyQuery.data.length > 0 && (
            <button
              className="btn btn-primary btn-sm mb-2 ms-3"
              type="button"
              onClick={() => setCreating(true)}
            >
              Create new key
            </button>
          )}
        </div>
        {creating && (
          <div className="sub_container anim__rightToLeft">
            <h4>Create new key</h4>
            <Form
              schema={editionSchema(tenant)}
              onSubmit={(key) => {
                return keyCreateMutation
                  .mutateAsync({ tenant, key: key as TKey })
                  .then((resp: any) => {
                    setSecret(resp.clientSecret);
                    setClientId(resp.clientId);
                  })
                  .then(() => setCreating(false));
              }}
              onClose={() => setCreating(false)}
            />
          </div>
        )}
        {keyQuery.data.length === 0 ? (
          !creating && (
            <div className="item-block">
              <div className="item-text">
                There is no key{hasTenantWriteRight ? "" : " you can see"} for
                this tenant.
              </div>
              {hasTenantWriteRight && (
                <button
                  type="button"
                  className="btn btn-primary btn-lg"
                  onClick={() => setCreating(true)}
                >
                  Create new key
                </button>
              )}
            </div>
          )
        ) : (
          <GenericTable
            columns={columns}
            data={keyQuery.data}
            idAccessor={(key) => key.name}
            filters={
              selectedSearchRow
                ? [{ id: "name", value: selectedSearchRow }]
                : []
            }
            customRowActions={{
              edit: {
                icon: (
                  <>
                    <i className="bi bi-pencil-square" aria-hidden></i> Edit
                  </>
                ),
                customForm(key, cancel) {
                  return (
                    <Form
                      schema={editionSchema(tenant, key)}
                      onClose={() => cancel()}
                      submitText="Update key"
                      onSubmit={(formResult: any) => {
                        return keyUpdateMutation
                          .mutateAsync({
                            oldName: key.name,
                            newKey: formResult,
                          })
                          .then(() => cancel());
                      }}
                    />
                  );
                },
              },
              rights: {
                icon: (
                  <>
                    <i className="fa-solid fa-lock" aria-hidden></i> Rights
                  </>
                ),
                hasRight: (user, key) =>
                  hasRightForKey(user, TLevel.Admin, key.name, tenant),
                customForm: (key, cancel) => {
                  return (
                    <>
                      <KeyRightTable tenant={tenant} apikey={key} />
                      <div className="d-flex justify-content-end">
                        <button className="btn btn-danger m-2" onClick={cancel}>
                          Cancel
                        </button>
                      </div>
                    </>
                  );
                },
              },
              delete: {
                icon: (
                  <>
                    <i className="bi bi-trash" aria-hidden></i> Delete
                  </>
                ),
                action: (key: TKey) => {
                  askInputConfirmation(
                    <>
                      Are you sure you want to delete key {key.name}? This can't
                      be undone.
                      <br />
                      Please confirm by typing key name below.
                    </>,
                    async () => {
                      try {
                        await keyDeleteMutation.mutateAsync({
                          name: key.name,
                        });
                      } catch (error) {
                        console.error("Error deleting:", error);
                        throw error;
                      }
                    },
                    key.name
                  );
                },
              },
            }}
          />
        )}
        <KeyModal
          id={clientid}
          visible={!!secret}
          secret={secret}
          onClose={() => {
            setSecret(undefined);
            setClientId(undefined);
          }}
        />
      </>
    );
  } else {
    return <Loader message="Loading keys ..." />;
  }
}

function KeyModal(props: {
  visible: boolean;
  secret?: string;
  id?: string;
  onClose: () => void;
}) {
  const { visible, secret, onClose, id } = props;
  return (
    <Modal title="Key created !" visible={visible} onClose={() => onClose()}>
      <>
        <label htmlFor="clientid">
          Key client id (izanami-client-id header)
        </label>
        <div className="input-group mb-3">
          <input
            id="clientid"
            name="clientid"
            type="text"
            className="form-control"
            aria-describedby="basic-addon2"
            value={id}
          />
          <div className="ms-2 input-group-append">
            <button
              className="btn btn-secondary"
              type="button"
              onClick={() => {
                navigator.clipboard.writeText(id!);
              }}
              aria-label="Copy client id"
            >
              Copy
            </button>
          </div>
        </div>
        <label htmlFor="secret">
          Key secret (izanami-client-secret header)
        </label>
        <div className="input-group mb-3">
          <input
            id="secret"
            name="secret"
            type="text"
            className="form-control"
            placeholder="Recipient's username"
            aria-label="Recipient's username"
            aria-describedby="basic-addon2"
            value={secret}
          />
          <div className="ms-2 input-group-append">
            <button
              className="btn btn-secondary"
              type="button"
              onClick={() => {
                navigator.clipboard.writeText(secret!);
              }}
              aria-label="Copy client secret"
            >
              Copy
            </button>
          </div>
        </div>
      </>
    </Modal>
  );
}

function KeyRightTable(props: { tenant: string; apikey: TKey }) {
  const { tenant, apikey: key } = props;
  const [creating, setCreating] = React.useState(false);

  const keyRightQuery = useQuery({
    queryKey: [keyUserQueryKey(tenant, key.name)],

    queryFn: () => fetchKeyUsers(tenant, key.name),
  });

  const keyRightUpdateMutation = useMutation({
    mutationFn: (data: { user: string; right?: TLevel }) =>
      updateKeyRightsFor(tenant, key.name, data.user, data.right),

    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: [keyUserQueryKey(tenant, key.name)],
      }),
  });

  if (keyRightQuery.error) {
    return <div>Failed to retrieve key users</div>;
  } else if (keyRightQuery.data) {
    return (
      <>
        <h4>
          Authorized users for {key.name}
          <button
            className="btn btn-secondary btn-sm ms-3"
            type="button"
            onClick={() => setCreating(true)}
          >
            Invite user
          </button>
        </h4>
        {creating && (
          <InvitationForm
            cancel={() => setCreating(false)}
            submit={({ users, level }) => {
              Promise.all(
                users.map((user) => {
                  keyRightUpdateMutation.mutateAsync({
                    user,
                    right: level,
                  });
                })
              ).then(() => setCreating(false));
            }}
            invitedUsers={keyRightQuery.data.map((key) => key.username)}
          />
        )}
        <RightTable
          data={keyRightQuery.data}
          canEdit={true}
          onRightChange={(datum, level) =>
            keyRightUpdateMutation.mutateAsync({
              user: datum.username,
              right: level,
            })
          }
        />
      </>
    );
  } else {
    return <Loader message="Loading key users..." />;
  }
}
