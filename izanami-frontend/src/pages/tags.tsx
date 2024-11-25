import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState, useContext } from "react";
import { Loader } from "../components/Loader";
import {
  createTag,
  deleteTag,
  queryTags,
  tagsQueryKey,
  updateTag,
} from "../utils/queries";
import { GenericTable } from "../components/GenericTable";
import { NavLink } from "react-router-dom";
import { Form } from "../components/Form";
import { constraints, format, type } from "@maif/react-forms";
import { TAG_NAME_REGEXP } from "../utils/patterns";
import queryClient from "../queryClient";
import { TLevel, TagType, TUser } from "../utils/types";
import {
  IzanamiContext,
  useTenantRight,
  hasRightForTenant,
} from "../securityContext";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { CopyButton } from "../components/FeatureTable";

export function Tags(props: { tenant: string }) {
  const { tenant } = props;
  const tagsQuery = useQuery({
    queryKey: [tagsQueryKey(tenant)],
    queryFn: () => queryTags(tenant),
  });
  const { askConfirmation } = useContext(IzanamiContext);
  const hasTenantWriteRight = useTenantRight(tenant, TLevel.Write);
  const [creating, setCreating] = useState<boolean>(false);

  const tagCreateMutation = useMutation({
    mutationFn: (data: TagType) =>
      createTag(tenant, data.name, data.description),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [tagsQueryKey(tenant!)] });
    },
  });

  const tagDeleteMutation = useMutation({
    mutationFn: ({ name }: { name: string }) => deleteTag(tenant!, name),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [tagsQueryKey(tenant!)] });
    },
  });
  const tagUpdateMutation = useMutation({
    mutationFn: ({
      currentName,
      tag,
    }: {
      currentName: string;
      tag: { name: string; description: string; id: string };
    }) => updateTag(tenant!, tag, currentName),

    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [tagsQueryKey(tenant!)] });
    },
  });

  const [bulkOperation, setBulkOperation] = useState<string | undefined>(
    undefined
  );

  const BULK_OPERATIONS = ["Delete"] as const;
  const [selectedRows, setSelectedRows] = useState<TagType[]>([]);
  const hasSelectedRows = selectedRows.length > 0;

  if (tagsQuery.error)
    return (
      <>
        <div className="item-block">
          <div className="item-text">
            There was an error fetching data for Tags.
          </div>
        </div>
      </>
    );
  else if (tagsQuery.data) {
    return (
      <>
        <div className="d-flex align-items-center">
          <h1>Tags </h1>
          {hasTenantWriteRight && !creating && tagsQuery.data.length > 0 && (
            <button
              type="button"
              className="btn btn-primary btn-sm mb-2 ms-3"
              onClick={() => setCreating(true)}
            >
              Create new tag
            </button>
          )}
        </div>
        {creating && (
          <div className="sub_container anim__rightToLeft">
            <h4>Create new tag</h4>
            <Form
              schema={{
                name: {
                  label: "Name",
                  type: type.string,
                  defaultValue: "",
                  required: true,
                  props: {
                    autoFocus: true,
                  },
                  constraints: [
                    constraints.matches(
                      TAG_NAME_REGEXP,
                      `Key name must match regex ${TAG_NAME_REGEXP.toString()}`
                    ),
                  ],
                },
                description: {
                  label: "Description",
                  type: type.string,
                  format: format.textarea,
                  defaultValue: "",
                },
              }}
              onSubmit={(tag: TagType) => {
                return tagCreateMutation
                  .mutateAsync(tag)
                  .then(() => setCreating(false));
              }}
              onClose={() => setCreating(false)}
            />
          </div>
        )}
        {tagsQuery.data.length === 0
          ? !creating && (
              <div className="item-block">
                <div className="item-text">
                  There is no tag{hasTenantWriteRight ? "" : " you can see"} for
                  this tenant.
                </div>
                {hasTenantWriteRight && (
                  <button
                    type="button"
                    className="btn btn-primary btn-lg"
                    onClick={() => setCreating(true)}
                  >
                    Create new tag
                  </button>
                )}
              </div>
            )
          : hasTenantWriteRight && (
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
                              } tag${selectedRows.length > 1 ? "s" : ""} ?`,
                              () => {
                                return Promise.all(
                                  selectedRows.map((row) =>
                                    tagDeleteMutation.mutateAsync(row)
                                  )
                                ).then(() => setBulkOperation(undefined));
                              }
                            );
                            break;
                        }
                      }}
                    >
                      {bulkOperation} {selectedRows.length} tag
                      {selectedRows.length > 1 ? "s" : ""}
                    </button>
                  </>
                )}
              </div>
            )}
        {tagsQuery.data.length > 0 && (
          <GenericTable
            selectableRows={hasTenantWriteRight}
            idAccessor={(tag) => tag.name}
            data={tagsQuery.data}
            columns={[
              {
                id: "name",
                accessorFn: (s) => s.name,
                header: () => "Tag name",
                minSize: 150,
                size: 15,
                cell: (info) => {
                  const name = info.getValue();
                  return (
                    <>
                      <div key={String(name)}>
                        <NavLink
                          className={() => ""}
                          to={`/tenants/${tenant}/tags/${name}`}
                        >
                          <span className="badge bg-warning text-dark">{`${name}`}</span>
                        </NavLink>
                      </div>
                    </>
                  );
                },
              },
              {
                id: "description",
                accessorFn: (s) => s.description,
                header: () => "Description",
                minSize: 150,
                size: 85,
              },

              {
                id: "ID",
                accessorFn: (s) => s.id,
                cell: (info) => {
                  return <CopyButton value={info.getValue()}></CopyButton>;
                },
                maxSize: 80,
                minSize: 60,
                size: 10,
              },
            ]}
            customRowActions={{
              edit: {
                icon: (
                  <>
                    <i className="bi bi-pencil-square" aria-hidden></i> Edit
                  </>
                ),
                hasRight: (user: TUser) => {
                  return Boolean(hasRightForTenant(user, tenant, TLevel.Write));
                },
                customForm(key, cancel) {
                  return (
                    <Form
                      schema={{
                        name: {
                          label: "Name",
                          type: type.string,
                          defaultValue: key?.name || "",
                          props: {
                            autoFocus: true,
                          },
                          required: true,
                          constraints: [
                            constraints.matches(
                              TAG_NAME_REGEXP,
                              `Key name must match regex ${TAG_NAME_REGEXP.toString()}`
                            ),
                          ],
                        },
                        description: {
                          label: "Description",
                          type: type.string,
                          format: format.textarea,
                          defaultValue: key?.description || "",
                        },
                      }}
                      onClose={() => cancel()}
                      submitText="Update tag"
                      onSubmit={(formResult: any) => {
                        return tagUpdateMutation
                          .mutateAsync({
                            tag: {
                              ...formResult,
                              id: key.id,
                            },
                            currentName: key.name,
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
                hasRight: (user: TUser) => {
                  return Boolean(hasRightForTenant(user, tenant, TLevel.Write));
                },
                action: (tag: TagType) => {
                  return new Promise((resolve, reject) => {
                    askConfirmation(
                      `Are you sure you want to delete tag ${tag.name} ?`,
                      () =>
                        tagDeleteMutation
                          .mutateAsync({ name: tag.name })
                          .then((res) => resolve(res))
                          .catch((err) => reject(err))
                    );
                  });
                },
              },
            }}
            onRowSelectionChange={(rows) => {
              setSelectedRows(rows);
            }}
            isRowSelectable={() => Boolean(hasTenantWriteRight)}
          />
        )}
      </>
    );
  } else {
    return <Loader message="Loading tags..." />;
  }
}
