import * as React from "react";
import {
  PersonnalAccessToken,
  TokenTenantRights,
  TokenTenantRightsArray,
} from "../utils/types";
import { format } from "date-fns";
import { GenericTable } from "./GenericTable";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  deletePersonnalAccessToken,
  personnalAccessTokenKey,
  queryPersonnalAccessTokens,
  updatePersonnalAccessToken,
} from "../utils/queries";
import { TokenForm } from "./TokenForm";
import queryClient from "../queryClient";
import { IzanamiContext } from "../securityContext";
import { ColumnDef } from "@tanstack/react-table";
import { Loader } from "./Loader";
import { Link } from "react-router-dom";
import { CopyButton } from "./FeatureTable";
import { tokenRightsToObject } from "../utils/rightUtils";

function tokenRightsToArray(rights: TokenTenantRights): TokenTenantRightsArray {
  return Object.entries(rights);
}

const columns: ColumnDef<PersonnalAccessToken>[] = [
  {
    accessorKey: "name",
    header: () => "Token name",
    minSize: 150,
    size: 25,
  },
  {
    id: "rights",
    header: () => "Rights",
    minSize: 200,
    size: 15,
    cell: (info: any) => {
      const token: PersonnalAccessToken = info.row.original;
      if ("rights" in token && Object.entries(token.rights).length === 0) {
        return "No rights";
      } else if ("rights" in token) {
        return Object.entries(token.rights).map(([tenant, rights]) => {
          return (
            <div key={`${token.id}/${tenant}`}>
              <Link to={`/tenants/${tenant}`}>
                <button className="btn btn-secondary btn-sm">
                  <i className="fas fa-cloud me-2" aria-hidden="true"></i>
                  {`${tenant}`}
                </button>
              </Link>{" "}
              <br />
              <ul style={{ marginBottom: 0 }}>
                {rights.map((r, index) => (
                  <li key={index}>{r}</li>
                ))}
              </ul>
            </div>
          );
        });
      } else {
        return "All rights";
      }
    },
  },
  {
    id: "expiresAt",
    header: () => "Expiration",
    minSize: 300,
    size: 30,
    cell: (info: any) => {
      const token: PersonnalAccessToken = info.row.original;
      if ("expiresAt" in token) {
        return `${format(token.expiresAt, "Pp")} (${token.expirationTimezone})`;
      }
    },
  },
  {
    accessorKey: "id",
    header: () => "ID",
    maxSize: 80,
    minSize: 60,
    size: 2,
    cell: (props) => {
      const value = props.row.original.id;

      return <CopyButton value={value} />;
    },
  },
  {
    id: "createdAt",
    header: () => "Created at",
    minSize: 175,
    size: 15,
    cell: (info: any) => {
      const token: PersonnalAccessToken = info.row.original;
      return format(token.createdAt, "Pp");
    },
  },
];

export function TokensTable(props: { user: string }) {
  const { askConfirmation } = React.useContext(IzanamiContext);
  const { user } = props;
  const tokenQuery = useQuery({
    queryKey: [personnalAccessTokenKey(user)],

    queryFn: () => {
      return queryPersonnalAccessTokens(user);
    },
  });

  const udpateQuery = useMutation({
    mutationFn: (data: {
      old: PersonnalAccessToken;
      name: string;
      expiresAt: Date;
      expirationTimezone: string;
      allRights: boolean;
      rights: TokenTenantRights;
    }) =>
      updatePersonnalAccessToken({
        username: user,
        id: data.old.id,
        expiresAt: data.expiresAt,
        expirationTimezone: data.expirationTimezone,
        name: data.name,
        createdAt: data.old.createdAt,
        allRights: data.allRights,
        rights: data.rights,
      }).then(() => {
        queryClient.invalidateQueries({
          queryKey: [personnalAccessTokenKey(user)],
        });
      }),
  });

  const deletionQuery = useMutation({
    mutationFn: (data: { id: string }) =>
      deletePersonnalAccessToken(user, data.id).then(() => {
        queryClient.invalidateQueries({
          queryKey: [personnalAccessTokenKey(user)],
        });
      }),
  });

  if (tokenQuery.data) {
    return (
      <GenericTable
        columns={columns}
        data={tokenQuery.data}
        idAccessor={(token) => token.id}
        customRowActions={{
          edit: {
            icon: (
              <>
                <i className="bi bi-pencil-square" aria-hidden></i> Edit
              </>
            ),
            customForm(token, cancel) {
              return (
                <>
                  <h3>Token edition</h3>
                  <TokenForm
                    defaultValue={{
                      ...token,
                      rights: token.rights
                        ? tokenRightsToArray(token.rights)
                        : [],
                    }}
                    onSubmit={(newValue) => {
                      return udpateQuery
                        .mutateAsync({
                          old: token,
                          ...newValue,
                          rights: newValue.allRights
                            ? {}
                            : tokenRightsToObject(newValue.rights),
                        })
                        .then(() => cancel());
                    }}
                    onCancel={() => cancel()}
                  />
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
            action: (token: PersonnalAccessToken) => {
              askConfirmation(
                `Are you sure you want to delete token ${token.name}?`,
                () => {
                  return deletionQuery.mutateAsync({ id: token.id });
                }
              );
            },
          },
        }}
      />
    );
  } else if (tokenQuery.error) {
    return (
      <div className="error-message">
        Failed to fetch personnal acess tokens
      </div>
    );
  } else {
    return <Loader message="Loading personnal access tokens" />;
  }
}
