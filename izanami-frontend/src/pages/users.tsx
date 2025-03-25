import { ColumnDef, Row } from "@tanstack/react-table";
import React, { useContext, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
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
import { RightSelector } from "../components/RightSelector";
import { TLevel, TRights, TTenantRight, TUser, UserType } from "../utils/types";
import { Modal } from "../components/Modal";
import { constraints } from "@maif/react-forms";
import { Form } from "../components/Form";
import { Loader } from "../components/Loader";
import { TokensTable } from "../components/TokensTable";
import { rightStateArrayToBackendMap } from "../utils/rightUtils";

export function Users() {
  const [creationUrl, setCreationUrl] = useState<string | undefined>(undefined);
  const { askConfirmation, user } = useContext(IzanamiContext);
  const [selectedRows, setSelectedRows] = useState<UserType[]>([]);
  const hasSelectedRows = selectedRows.length > 0;
  const [bulkOperation, setBulkOperation] = useState<string | undefined>(
    undefined
  );
  const BULK_OPERATIONS = ["Delete", "Toggle Admin Role"] as const;
  const isAdmin = useAdmin();
  const context = useContext(IzanamiContext);
  const isTenantAdmin = Boolean(
    Object.values(context.user?.rights.tenants || {}).find(
      (tenantRight) => tenantRight.level == TLevel.Admin
    )
  );
  const [creating, setCreating] = useState(false);
  const userQuery = useQuery({
    queryKey: [MutationNames.USERS],
    queryFn: () => usersQuery(),
  });
  const userDeleteMutation = useMutation({
    mutationFn: (username: string) => deleteUser(username),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [MutationNames.USERS] });
      setSelectedRows([]);
    },
  });

  const userUpdateMutation = useMutation({
    mutationFn: (user: {
      username: string;
      admin: boolean;
      rights: TRights;
    }) => {
      const { username, ...rest } = user;
      return updateUserRights(username, rest);
    },

    onSuccess: (_, { username }) => {
      queryClient.invalidateQueries({ queryKey: [MutationNames.USERS] });
      queryClient.invalidateQueries({ queryKey: [userQueryKey(username)] });
    },
  });

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
      queryClient.invalidateQueries({ queryKey: [userQueryKey(username)] });
    },
  });
  const inviteUserMutation = useMutation({
    mutationFn: (data: {
      admin: boolean;
      email: string;
      rights: TRights;
      userToCopy: string;
    }) => {
      if (data.userToCopy) {
        return queryUser(data.userToCopy).then((res: TUser) => {
          return createInvitation(data.email, res.admin, res.rights);
        });
      }
      return createInvitation(data.email, data.admin, data.rights);
    },
  });

  function OperationToggleForm(props: {
    bulkOperation: string;
    selectedRows: UserType[];
    cancel: () => void;
  }) {
    const { bulkOperation, selectedRows, cancel } = props;

    switch (bulkOperation) {
      case "Toggle Admin Role": {
        const adminOptions = [
          {
            label: "Add Admin right",
            value: "true",
          },
          {
            label: "Remove Admin right",
            value: "false",
          },
        ];

        return (
          <>
            <Form
              className={"d-flex align-items-center"}
              schema={{
                admin: {
                  className: "form-margin",
                  label: () => "",
                  type: "string",
                  format: "select",
                  props: { styles: customStyles },
                  placeholder: "Select Admin Role...",
                  options: adminOptions,
                },
              }}
              onSubmit={(ctx) => {
                return askConfirmation(
                  `Are you sure you want to change admin role for ${
                    selectedRows.length
                  } user${selectedRows.length > 1 ? "s" : ""}?`,
                  () => {
                    return Promise.all(
                      selectedRows.map((row) => {
                        return fetch(`/api/admin/users/${row.username}`)
                          .then((response) => {
                            return response.json();
                          })
                          .then((data) => {
                            return userUpdateMutation.mutateAsync({
                              username: row.username,
                              admin: ctx.admin === "true",
                              rights: data.rights,
                            });
                          });
                      })
                    ).then(cancel);
                  }
                );
              }}
              footer={({ valid }: { valid: () => void }) => {
                return (
                  <div className="d-flex justify-content-end">
                    <button
                      type="button"
                      className="btn btn-danger-light m-2"
                      onClick={cancel}
                    >
                      Cancel
                    </button>
                    <button className="btn btn-primary m-2" onClick={valid}>
                      Update {selectedRows.length} User
                      {selectedRows.length > 1 ? "s" : ""}
                    </button>
                  </div>
                );
              }}
            />
          </>
        );
      }
      default:
        return (
          <button
            className="ms-2 btn btn-primary"
            type="button"
            disabled={!hasSelectedRows || !bulkOperation}
            onClick={() =>
              askConfirmation(
                `Are you sure you want to delete ${selectedRows.length} user${
                  selectedRows.length > 1 ? "s" : ""
                } ?`,
                () => {
                  return Promise.all(
                    selectedRows.map((row) =>
                      userDeleteMutation
                        .mutateAsync(row.username)
                        .then(() => setBulkOperation(undefined))
                    )
                  );
                }
              )
            }
          >
            {bulkOperation} {selectedRows.length} user
            {selectedRows.length > 1 ? "s" : ""}
          </button>
        );
    }
  }
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
                    defaultValue: "",
                  },
                  useCopyUserRights: {
                    label: "Copy rights from another user",
                    type: "bool",
                  },
                  admin: {
                    label: "Admin",
                    type: "bool",
                    visible: ({ rawValues }) => !rawValues.useCopyUserRights,
                  },
                  rights: {
                    label: () => "",
                    type: "object",
                    array: true,
                    visible: ({ rawValues }) => !rawValues.useCopyUserRights,
                    render: ({ onChange }) => (
                      <RightSelector
                        tenantLevelFilter="Admin"
                        onChange={(v) => {
                          onChange?.(v);
                        }}
                      />
                    ),
                  },
                  userToCopy: {
                    label: "User to copy",
                    type: "object",
                    visible: ({ rawValues }) => rawValues.useCopyUserRights,
                    render: ({ onChange }) => (
                      <Select
                        isClearable
                        styles={customStyles}
                        options={userQuery.data.map(({ username }) => ({
                          label: username,
                          value: username,
                        }))}
                        onChange={(v) => {
                          onChange?.(v);
                        }}
                      />
                    ),
                  },
                }}
                onSubmit={(ctx) => {
                  const backendRights = rightStateArrayToBackendMap(ctx.rights);
                  const payload = {
                    rights: backendRights,
                    admin: ctx.admin,
                    email: ctx.email,
                    userToCopy: ctx.userToCopy?.value,
                  };

                  return inviteUserMutation
                    .mutateAsync(payload)
                    .then((response) => {
                      if (response && response.invitationUrl) {
                        setCreationUrl(response.invitationUrl);
                      }
                      setCreating(false);
                    });
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
            <OperationToggleForm
              bulkOperation={bulkOperation}
              selectedRows={selectedRows}
              cancel={() => setBulkOperation(undefined)}
            />
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
                    username={username}
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
            tokens: {
              hasRight: (user, rowUser) =>
                isAdmin && rowUser.username !== user.username,
              icon: (
                <>
                  <i className="fas fa-key" aria-hidden></i> Tokens
                </>
              ),
              customForm: (data, cancel) => {
                return (
                  <div>
                    <h4>Personnal access tokens for {data.username}</h4>
                    <TokensTable user={data.username} />
                    <div className="d-flex justify-content-end">
                      <button
                        type="button"
                        className="btn btn-danger m-2"
                        onClick={() => cancel()}
                      >
                        Close
                      </button>
                    </div>
                  </div>
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
  username: string;
  submit: any;
  cancel: () => void;
  tenant?: string;
}) {
  const { username, submit, cancel } = props;
  const context = useContext(IzanamiContext);
  const isAdmin = useAdmin();
  const adminTenants: string[] = Object.entries(
    context.user?.rights.tenants || {}
  )
    .filter(([, tenantRight]) => tenantRight.level == TLevel.Admin)
    .map(([name]) => name);

  const userQuery = useQuery({
    queryKey: [
      props.tenant
        ? userQueryKeyForTenant(username, props.tenant)
        : userQueryKey(username),
    ],

    queryFn: () => {
      if (isAdmin && !props.tenant) {
        return queryUser(username);
      } else {
        return queryUserForTenants(
          username,
          props.tenant ? [props.tenant] : adminTenants
        );
      }
    },
  });

  if (userQuery.isLoading) {
    return <Loader message="Loading..." />;
  } else if (userQuery.data) {
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
              visible: () => !props.tenant && isAdmin,
            },
            rights: {
              label: () => "",
              type: "object",
              array: true,
              render: ({ onChange }) => {
                return (
                  <RightSelector
                    defaultValue={rights}
                    tenant={props.tenant}
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
