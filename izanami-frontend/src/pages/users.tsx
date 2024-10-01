import { ColumnDef, Row } from "@tanstack/react-table";
import React, { useContext, useState } from "react";
import { useMutation, useQuery } from "react-query";
import { GenericTable } from "../components/GenericTable";
import queryClient from "../queryClient";
import { IzanamiContext, useAdmin } from "../securityContext";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";

import {
  createInvitation,
  deleteUser,
  MutationNames,
  queryUser,
  queryUserForTenants,
  updateUserRights,
  updateUserRightsForTenant,
  userQueryKey,
  userQueryKeyForTenant,
  usersQuery,
} from "../utils/queries";
import { Link } from "react-router-dom";
import { isEqual } from "lodash";
import {
  RightSelector,
  rightStateArrayToBackendMap,
} from "../components/RightSelector";
import { TLevel, TRights, TTenantRight, TUser, UserType } from "../utils/types";
import { Modal } from "../components/Modal";
import { constraints } from "@maif/react-forms";
import { Form } from "../components/Form";
import { Loader } from "../components/Loader";

export function Users() {
  const [creationUrl, setCreationUrl] = useState<string | undefined>(undefined);
  const { askConfirmation, user } = useContext(IzanamiContext);
  const [selectedRows, setSelectedRows] = useState<UserType[]>([]);
  const hasSelectedRows = selectedRows.length > 0;
  const [bulkOperation, setBulkOperation] = useState<string | undefined>(
    undefined
  );

  const BULK_OPERATIONS = ["Delete"] as const;
  const isAdmin = useAdmin();
  const context = useContext(IzanamiContext);
  const isTenantAdmin = Boolean(
    Object.values(context.user?.rights.tenants || {}).find(
      (tenantRight) => tenantRight.level == TLevel.Admin
    )
  );
  const [creating, setCreating] = useState(false);
  const userQuery = useQuery(MutationNames.USERS, () => usersQuery());
  const userDeleteMutation = useMutation(
    (username: string) => deleteUser(username),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(MutationNames.USERS);
        setSelectedRows([]);
      },
    }
  );

  const userUpdateMutation = useMutation(
    (user: { username: string; admin: boolean; rights: TRights }) => {
      const { username, ...rest } = user;
      return updateUserRights(username, rest);
    },
    {
      onSuccess: (_, { username }) => {
        queryClient.invalidateQueries(MutationNames.USERS);
        queryClient.invalidateQueries(userQueryKey(username));
      },
    }
  );

  const userUpdateMutationForTenant = useMutation(
    (user: { username: string; tenant: string; rights: TTenantRight }) => {
      const { username, tenant, rights } = user;
      return updateUserRightsForTenant(username, tenant, rights);
    },
    {
      onSuccess: (_, { username }) => {
        queryClient.invalidateQueries(userQueryKey(username));
      },
    }
  );

  const inviteUserMutation = useMutation(
    (data: { email: string; admin: boolean; rights: TRights }) =>
      createInvitation(data.email, data.admin, data.rights)
  );

  if (userQuery.isLoading) {
    return <Loader message="Loading users..." />;
  } else if (userQuery.isSuccess) {
    const users = userQuery.data;
    const columns: ColumnDef<TUser>[] = [
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
        accessorFn: (row: any) => {
          return Object.keys(row.tenantRights || {});
        },
        header: "Tenants",
        meta: {
          valueType: "discrete",
        },
        cell: (props: { row: any }) => {
          // FIXME TS
          const user = props.row.original!;
          return Object.keys(user?.tenantRights || {}).map(
            (tenantName: string) => (
              <Link
                to={`/tenants/${tenantName}`}
                key={`${user.username}-${tenantName}`}
              >
                <button className="btn btn-secondary btn-sm my-1">
                  <i className="fas fa-cloud me-2" aria-hidden="true"></i>
                  {`${tenantName}`}
                </button>
              </Link>
            )
          );
        },
        filterFn: (row: Row<any>, columnId: string, filterValue: any) => {
          if (!filterValue || filterValue?.length === 0) {
            return true;
          }
          const value: any = row.getValue(columnId);

          return (
            row.original.admin ||
            filterValue.some((v: string) => value.includes(v))
          );
        },
        size: 50,
      },
      {
        accessorKey: "userType",
        header: () => "User type",
        meta: {
          valueType: "discrete",
        },
        size: 15,
      },
    ];
    return (
      <>
        <div className="d-flex align-items-center">
          <h1>Users</h1>
          {(isAdmin || isTenantAdmin) && (
            <button
              className="btn btn-secondary btn-sm mb-2 ms-3"
              type="button"
              onClick={() => setCreating(true)}
            >
              Invite new user
            </button>
          )}
        </div>
        {creating && (
          <>
            <div className="sub_container anim__rightToLeft" role="form">
              <h4 className="my-2">Invite user</h4>
              <Form
                schema={{
                  email: {
                    label: "Email to invite",
                    type: "string",
                    format: "email",
                    required: true,
                    constraints: [
                      constraints.email("Email format is incorrect"),
                    ],
                    props: {
                      autoFocus: true,
                    },
                  },
                  admin: {
                    label: "Admin",
                    type: "bool",
                  },
                  rights: {
                    label: () => "",
                    type: "object",
                    array: true,
                    render: ({ onChange }) => {
                      return (
                        <RightSelector
                          tenantLevelFilter="Admin"
                          onChange={(v) => onChange?.(v)}
                        />
                      );
                    },
                  },
                }}
                onSubmit={(ctx) => {
                  const backendRights = rightStateArrayToBackendMap(ctx.rights);

                  const payload = {
                    rights: backendRights,
                    admin: ctx.admin,
                    email: ctx.email,
                  };

                  return inviteUserMutation
                    .mutateAsync(payload)
                    .then((response) => {
                      if (response && response.invitationUrl) {
                        setCreationUrl(response.invitationUrl);
                      }
                      return response;
                    })
                    .then(() => setCreating(false));
                }}
                onClose={() => setCreating(false)}
                submitText="Send invitation"
              />
            </div>
          </>
        )}
        <div
          className={`d-flex align-items-center ${
            hasSelectedRows ? "" : "invisible"
          }`}
        >
          <Select
            options={BULK_OPERATIONS.map((op) => ({
              label: op,
              value: op,
            }))}
            value={
              bulkOperation
                ? { label: bulkOperation, value: bulkOperation }
                : null
            }
            onChange={(e) => setBulkOperation(e?.value)}
            styles={customStyles}
            isClearable={true}
            isDisabled={selectedRows?.length === 0}
            placeholder="Bulk action"
            aria-label="Bulk action"
          />
          &nbsp;
          {bulkOperation && (
            <>
              <button
                className="ms-2 btn btn-primary"
                type="button"
                disabled={!hasSelectedRows || !bulkOperation}
                onClick={() => {
                  switch (bulkOperation) {
                    case "Delete":
                      askConfirmation(
                        `Are you sure you want to delete ${
                          selectedRows.length
                        } user${selectedRows.length > 1 ? "s" : ""} ?`,
                        () => {
                          return Promise.all(
                            selectedRows.map((row) =>
                              userDeleteMutation
                                .mutateAsync(row.username)
                                .then(() => setBulkOperation(undefined))
                            )
                          );
                        }
                      );
                      break;
                  }
                }}
              >
                {bulkOperation} {selectedRows.length} user
                {selectedRows.length > 1 ? "s" : ""}
              </button>
            </>
          )}
        </div>
        <GenericTable
          selectableRows={user?.admin}
          data={users}
          onRowSelectionChange={(rows) => {
            setSelectedRows(rows);
          }}
          isRowSelectable={(row) => row.username !== user?.username}
          columns={columns}
          idAccessor={(u) => u.username}
          customRowActions={{
            edit: {
              icon: (
                <>
                  <i className="bi bi-pencil-square" aria-hidden></i> Edit
                </>
              ),
              hasRight: (user, rowUser) =>
                (isAdmin || isTenantAdmin) &&
                rowUser.username !== user.username,
              customForm: (data, cancel) => {
                const { username } = data;
                return (
                  <UserEdition
                    user={{ username }}
                    submit={(newItem: any, old: TUser) => {
                      if (isAdmin) {
                        return userUpdateMutation
                          .mutateAsync({
                            username: old.username,
                            ...newItem,
                          })
                          .then(() => cancel());
                      } else {
                        return Promise.all(
                          Object.entries(newItem.rights.tenants)
                            .filter(([tenantName, tenantRight]) => {
                              return !isEqual(
                                tenantRight,
                                old.rights.tenants?.[tenantName]
                              );
                            })
                            .map(([tenantName, tenantRight]) => {
                              return userUpdateMutationForTenant.mutateAsync({
                                username: old.username,
                                tenant: tenantName,
                                rights: tenantRight as TTenantRight,
                              });
                            })
                        ).then(() => cancel());
                      }
                    }}
                    cancel={cancel}
                  />
                );
              },
            },
            delete: {
              hasRight: (user, rowUser) =>
                isAdmin && rowUser.username !== user.username,
              icon: (
                <>
                  <i className="bi bi-trash" aria-hidden></i> Delete
                </>
              ),
              action: (user: UserType) => {
                return askConfirmation(
                  `Are you sure you want to delete user ${user.username} ?`,
                  () => userDeleteMutation.mutateAsync(user.username)
                );
              },
            },
          }}
        />
        <UrlModal
          visible={!!creationUrl}
          onClose={() => setCreationUrl(undefined)}
          url={creationUrl!}
        />
      </>
    );
  } else {
    return <div>Error while fetching users</div>;
  }
}

function UrlModal(props: {
  visible: boolean;
  onClose: () => void;
  url: string;
}) {
  const { visible, onClose, url } = props;

  const [copied, setCopied] = useState(false);
  const [warningVisible, setWarningVisible] = useState(false);
  return (
    <Modal
      visible={visible}
      onClose={() => {
        if (copied || warningVisible) {
          setWarningVisible(false);
          onClose();
        } else {
          setWarningVisible(true);
        }
      }}
    >
      <>
        <label htmlFor="secret">Send this url to the new user</label>
        <p className="text-warning">It won't be displayed again</p>
        <div className="input-group mb-3">
          <input
            id="secret"
            name="secret"
            type="text"
            className="form-control"
            aria-label="Invitation url"
            value={url}
            onFocus={() => setCopied(true)}
          />
          <div className="ms-2 input-group-append">
            <button
              className="btn btn-primary"
              type="button"
              onClick={() => {
                navigator.clipboard.writeText(url);
                setCopied(true);
              }}
            >
              Copy
            </button>
          </div>
        </div>
        {warningVisible && (
          <span style={{ color: "#D5443F" }}>
            Please make sure you copied the url before closing this dialog, this
            url won't be displayed again
          </span>
        )}
      </>
    </Modal>
  );
}

export function UserEdition(props: {
  user: { username: string; right?: string };
  submit: any;
  cancel: () => void;
  tenant?: string;
}) {
  const { user, submit, cancel, tenant } = props;
  const context = useContext(IzanamiContext);
  const isAdmin = useAdmin();
  const adminTenants: string[] = Object.entries(
    context.user?.rights.tenants || {}
  )
    .filter(([, tenantRight]) => tenantRight.level == TLevel.Admin)
    .map(([name]) => name);

  const userQuery = useQuery(
    tenant && typeof user.right !== "undefined"
      ? userQueryKeyForTenant(user.username, tenant)
      : userQueryKey(user.username),
    () => {
      if (isAdmin && (!tenant || typeof user.right === "undefined")) {
        return queryUser(user.username);
      } else {
        return queryUserForTenants(
          user.username,
          tenant ? [tenant] : adminTenants
        );
      }
    }
  );

  if (userQuery.isLoading) {
    return <Loader message="Loading..." />;
  }
  if (userQuery.isError) {
    return <div>Error loading user information</div>;
  }
  if (userQuery.data) {
    let { rights, admin } = userQuery.data;
    return (
      <div className="sub_container">
        <h4>User rights</h4>
        <Form
          schema={{
            admin: {
              type: "bool",
              label: "Admin",
              defaultValue: admin,
              visible: () => !tenant && isAdmin,
            },
            rights: {
              label: () => "",
              type: "object",
              array: true,
              render: ({ onChange }) => {
                return (
                  <RightSelector
                    defaultValue={rights}
                    tenant={tenant}
                    onChange={(v) => onChange?.(v)}
                  />
                );
              },
            },
          }}
          onSubmit={(ctx) => {
            const backendRights = rightStateArrayToBackendMap(ctx.rights);

            const payload = {
              rights: backendRights,
              admin: ctx.admin,
            };

            return submit(payload, userQuery.data);
          }}
          onClose={() => cancel()}
          submitText="Update rights"
        />
      </div>
    );
  } else {
    return <div>Error loading user</div>;
  }
}
