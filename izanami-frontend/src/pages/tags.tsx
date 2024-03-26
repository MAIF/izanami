import * as React from "react";
import { useMutation, useQuery } from "react-query";
import {
  createTag,
  deleteTag,
  queryTags,
  tagsQueryKey,
  updateTag,
} from "../utils/queries";
import { GenericTable } from "../components/GenericTable";
import { NavLink } from "react-router-dom";
import { Form, constraints, format, type } from "@maif/react-forms";
import { TAG_NAME_REGEXP } from "../utils/patterns";
import queryClient from "../queryClient";
import { TLevel, TagType } from "../utils/types";
import { IzanamiContext, useTenantRight } from "../securityContext";

export function Tags(props: { tenant: string }) {
  const { tenant } = props;
  const tagsQuery = useQuery(tagsQueryKey(tenant), () => queryTags(tenant));
  const { askConfirmation } = React.useContext(IzanamiContext);
  const hasTenantWriteRight = useTenantRight(tenant, TLevel.Write);
  const [creating, setCreating] = React.useState<boolean>(false);

  const tagCreateMutation = useMutation(
    (data: TagType) => createTag(tenant, data.name, data.description),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(tagsQueryKey(tenant!));
      },
    }
  );

  const tagDeleteMutation = useMutation(
    ({ name }: { name: string }) => deleteTag(tenant!, name),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(tagsQueryKey(tenant!));
      },
    }
  );
  const tagUpdateMutation = useMutation(
    ({
      currentName,
      tag,
    }: {
      currentName: string;
      tag: { name: string; description: string; id: string };
    }) => updateTag(tenant!, tag, currentName),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(tagsQueryKey(tenant!));
      },
    }
  );

  if (tagsQuery.error) return <div>Error while fetching tags</div>;
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
                  props: {
                    autoFocus: true,
                  },
                  constraints: [
                    constraints.required("Name is required"),
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
                tagCreateMutation
                  .mutateAsync(tag)
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
        {tagsQuery.data.length === 0 ? (
          !creating && (
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                justifyContent: "center",
                alignItems: "center",
                marginTop: "10%",
              }}
            >
              <div style={{ fontSize: "21px", marginBottom: "24px" }}>
                There is no Tag for this tenant.
              </div>
              <button
                type="button"
                className="btn btn-primary btn-lg"
                onClick={() => setCreating(true)}
              >
                Create new Tag
              </button>
            </div>
          )
        ) : (
          <GenericTable
            idAccessor={(tag) => tag.name}
            data={tagsQuery.data}
            columns={[
              {
                id: "name",
                accessorFn: (s) => s.name,
                header: () => "Tag name",
                minSize: 200,
                cell: (info) => {
                  const name = info.getValue();
                  return (
                    <>
                      <div key={name}>
                        <NavLink
                          className={() => ""}
                          to={`/tenants/${tenant}/tags/${name}`}
                        >
                          {name}
                        </NavLink>
                      </div>
                    </>
                  );
                },
                size: 10,
              },
              {
                id: "description",
                accessorFn: (s) => s.description,
                header: () => "Description",
                minSize: 200,
                size: 10,

                meta: {
                  valueType: "discrete",
                },
              },
            ]}
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
                      schema={{
                        name: {
                          label: "Name",
                          type: type.string,
                          defaultValue: key?.name || "",
                          props: {
                            autoFocus: true,
                          },
                          constraints: [
                            constraints.required("Name is required"),
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
                              Update tag
                            </button>
                          </div>
                        );
                      }}
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
          />
        )}
      </>
    );
  } else {
    return <div>Loading...</div>;
  }
}
