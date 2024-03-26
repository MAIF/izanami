import * as React from "react";
import { useMutation, useQuery } from "react-query";
import {
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
import { TagType } from "../utils/types";
import { IzanamiContext } from "../securityContext";

export function Tags(props: { tenant: string }) {
  const { tenant } = props;
  const tagsQuery = useQuery(tagsQueryKey(tenant), () => queryTags(tenant));
  const { askConfirmation } = React.useContext(IzanamiContext);
  const keyDeleteMutation = useMutation(
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

  if (tagsQuery.error) return <div>Error with tags</div>;
  else if (tagsQuery.data) {
    return (
      <>
        <h1>Tags</h1>
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
                      keyDeleteMutation
                        .mutateAsync({ name: tag.name })
                        .then((res) => resolve(res))
                        .catch((err) => reject(err))
                  );
                });
              },
            },
          }}
        />
      </>
    );
  } else {
    return <div>loading</div>;
  }
}
