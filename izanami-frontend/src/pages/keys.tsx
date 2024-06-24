import * as React from "react";
import { useMutation, useQuery } from "react-query";
import { ColumnDef, Row } from "@tanstack/react-table";
import {
  createKey,
  deleteKey,
  queryKeys,
  tenantKeyQueryKey,
  updateKey,
} from "../utils/queries";
import { ProjectType, TKey, TLevel } from "../utils/types";
import queryClient from "../queryClient";
import { Form } from "../components/Form";
import { constraints, format, type } from "@maif/react-forms";
import { GenericTable } from "../components/GenericTable";
import { customStyles } from "../styles/reactSelect";
import { Modal } from "../components/Modal";
import { IzanamiContext, useTenantRight } from "../securityContext";
import { KEY_NAME_REGEXP } from "../utils/patterns";
import { Loader } from "../components/Loader";

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
      label: "Enabled",
      type: type.bool,
      defaultValue: key?.enabled ?? false,
      props: {
        "aria-label": "Enabled",
        id: "enabled",
      },
    },
    admin: {
      label: "Admin",
      type: type.bool,
      defaultValue: key?.admin ?? false,
    },
    projects: {
      label: "Allowed projects",
      type: type.string,
      format: format.select,
      isMulti: true,
      optionsFrom: `/api/admin/tenants/${tenant}/projects`,
      props: { "aria-label": "projects", styles: customStyles },
      transformer: (project: ProjectType) => {
        return { label: project.name, value: project.name };
      },
      defaultValue: key?.projects || undefined,
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
  const { tenant } = props;
  const [secret, setSecret] = React.useState<string | undefined>(undefined);
  const [clientid, setClientId] = React.useState<string | undefined>(undefined);
  const keyQuery = useQuery(tenantKeyQueryKey(tenant), () => queryKeys(tenant));
  const hasTenantWriteRight = useTenantRight(tenant, TLevel.Write);
  const [creating, setCreating] = React.useState(false);
  const { askConfirmation, refreshUser } = React.useContext(IzanamiContext);
  const keyDeleteMutation = useMutation(
    tenantKeyQueryKey(tenant),
    (name: string) => deleteKey(tenant, name),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(tenantKeyQueryKey(tenant));
        refreshUser();
      },
    }
  );
  const keyUpdateMutation = useMutation(
    tenantKeyQueryKey(tenant),
    (params: { oldName: string; newKey: TKey }) =>
      updateKey(tenant, params.oldName, params.newKey),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(tenantKeyQueryKey(tenant));
      },
    }
  );
  const keyCreateMutation = useMutation(
    tenantKeyQueryKey(tenant),
    (params: { key: TKey; tenant: string }) =>
      createKey(params.tenant, params.key),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(tenantKeyQueryKey(tenant));
      },
    }
  );

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
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
              overflow: "hidden",
              maxWidth: "110px",
            }}
          >
            {info.getValue()}
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
            style={{
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
              overflow: "hidden",
              maxWidth: "75px",
            }}
          >
            {info.row.original.clientSecret}
          </div>
        );
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
    {
      accessorKey: "projects",
      header: () => "Projects",
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

        return filterValue.some((v: string) => value.includes(v));
      },
    },
    {
      accessorKey: "admin",
      header: () => "Admin",
      meta: {
        valueType: "boolean",
      },
      size: 10,
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
              footer={({ valid }: { valid: () => void }) => (
                <div className="d-flex justify-content-end mt-3">
                  <button
                    type="button"
                    className="btn btn-danger m-2"
                    onClick={() => setCreating(false)}
                  >
                    Cancel
                  </button>
                  <button className="btn btn-success m-2" onClick={valid}>
                    Save
                  </button>
                </div>
              )}
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
            data={keyQuery.data ?? []}
            idAccessor={(key) => key.name}
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
                      footer={({ valid }: { valid: () => void }) => {
                        return (
                          <div className="d-flex justify-content-end">
                            <button
                              type="button"
                              className="btn btn-danger m-2"
                              onClick={() => cancel()}
                            >
                              Cancel
                            </button>
                            <button
                              className="btn btn-success m-2"
                              onClick={valid}
                            >
                              Update key
                            </button>
                          </div>
                        );
                      }}
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
              delete: {
                icon: (
                  <>
                    <i className="bi bi-trash" aria-hidden></i> Delete
                  </>
                ),
                action: (key: TKey) => {
                  return new Promise((resolve, reject) => {
                    askConfirmation(
                      `Are you sure you want to delete key ${key.name} ?`,
                      () =>
                        keyDeleteMutation
                          .mutateAsync(key.name)
                          .then((res) => resolve(res))
                          .catch((err) => reject(err))
                    );
                  });
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
        <label htmlFor="clientid">Key client id</label>
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
        <label htmlFor="secret">Key secret</label>
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
              className="btn btn-primary"
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
