import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  deleteProject,
  inviteUsersToProject,
  projectQueryKey,
  projectUserQueryKey,
  queryProject,
  queryProjectUsers,
  updateProject,
  updateUserRightsForProject,
} from "../utils/queries";
import { useNavigate } from "react-router-dom";
import { useProjectRight } from "../securityContext";
import queryClient from "../queryClient";
import { constraints, format, type } from "@maif/react-forms";
import { Form } from "../components/Form";
import { useState } from "react";
import { TLevel } from "../utils/types";
import { GenericTable } from "../components/GenericTable";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { InvitationForm } from "../components/InvitationForm";
import { PROJECT_NAME_REGEXP } from "../utils/patterns";
import { Loader } from "../components/Loader";
import { IzanamiContext } from "../securityContext";

export function ProjectSettings(props: { project: string; tenant: string }) {
  const { project, tenant } = props;
  const { askInputConfirmation } = React.useContext(IzanamiContext);
  const queryKey = projectQueryKey(tenant, project);
  const projectQuery = useQuery({
    queryKey: [queryKey],
    queryFn: () => queryProject(tenant, project),
  });
  const usersQuery = useQuery({
    queryKey: [projectUserQueryKey(tenant, project)],

    queryFn: () => queryProjectUsers(tenant, project),
  });

  const [modification, setModification] = useState(false);
  const navigate = useNavigate();

  const projectDelete = useMutation({
    mutationFn: (data: { tenant: string; project: string }) =>
      deleteProject(data.tenant, data.project),

    onSuccess: () => {
      navigate(`/tenants/${tenant}`);
    },
  });

  const inviteUsers = useMutation({
    mutationFn: (data: { users: string[]; level: TLevel }) => {
      const { users, level } = data;
      return inviteUsersToProject(tenant, project, users, level);
    },

    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: [projectUserQueryKey(tenant, project)],
      });
    },
  });
  const [inviting, setInviting] = useState(false);

  if (projectQuery.isLoading || usersQuery.isLoading) {
    return <Loader message="Loading project / project users..." />;
  }
  if (projectQuery.isSuccess && usersQuery.isSuccess) {
    return (
      <>
        <h1>Settings</h1>
        <h2 className="mt-5">
          Project users{" "}
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
            invitedUsers={usersQuery.data.map((user) => user.username) || []}
          />
        )}

        <ProjectUsers
          tenant={tenant}
          project={project}
          usersData={usersQuery.data}
        />
        <hr />
        <h2 className="mt-4">Danger zone</h2>
        <div className="border border-danger rounded p-2 mt-3">
          <div className="d-flex align-items-center justify-content-between p-2">
            <span>Update the name and the description of the project</span>
            <button
              type="button"
              className="btn btn-sm btn-danger"
              onClick={() => {
                setModification(true);
              }}
            >
              Update project
            </button>
          </div>

          {modification && (
            <ProjectModification
              tenant={tenant}
              project={projectQuery.data}
              onDone={() => setModification(false)}
            />
          )}
          <hr />
          <div className="d-flex align-items-center justify-content-between p-2">
            <span>Delete this project</span>

            <button
              type="button"
              className="btn btn-sm btn-danger"
              onClick={() =>
                askInputConfirmation(
                  <>
                    All features will be deleted, this cannot be undone.
                    <br />
                    Please confirm by typing project name below.
                  </>,
                  async () => {
                    try {
                      await projectDelete.mutateAsync({
                        project,
                        tenant,
                      });
                    } catch (error) {
                      console.error("Error deleting:", error);
                      throw error;
                    }
                  },
                  project,
                  `Delete project / ${project}`
                )
              }
            >
              Delete project
            </button>
          </div>
        </div>
      </>
    );
  } else {
    return <div>Failed to fetch project / project users</div>;
  }
}

function ProjectUsers(props: {
  tenant: string;
  project: string;
  usersData: any;
}) {
  const { tenant, project, usersData } = props;

  const userUpdateMutationForProject = useMutation({
    mutationFn: (user: { username: string; right?: TLevel }) => {
      const { username, right } = user;
      return updateUserRightsForProject(username, tenant, project, right);
    },
  });
  const isProjectAdmin = useProjectRight(tenant, project, TLevel.Admin);
  return (
    <>
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
              (isProjectAdmin ?? false) && currentUser.username !== username,
            customForm: (data, cancel) => (
              <ProjectRightLevelModification
                submit={(oldItem, newItem) => {
                  let { level } = newItem;
                  if (!Object.values(TLevel).includes(level)) {
                    level = undefined;
                  }
                  return userUpdateMutationForProject.mutateAsync(
                    {
                      username: oldItem.username,
                      right: level,
                    },
                    {
                      onSuccess: () => {
                        cancel();
                        queryClient.invalidateQueries({
                          queryKey: [projectUserQueryKey(tenant, project)],
                        });
                      },
                    }
                  );
                }}
                cancel={cancel}
                username={data.username}
                name={project}
                level={data.right}
              />
            ),
          },
        }}
        columns={[
          {
            accessorKey: "username",
            header: () => "Username",
            size: 25,
          },
          {
            accessorKey: "admin",
            header: () => "Admin",
            meta: {
              valueType: "boolean",
            },
            size: 20,
          },
          {
            // eslint-disable-next-line @typescript-eslint/ban-ts-comment
            // @ts-ignore
            accessorKey: "tenantAdmin", // FIXME divergent user types (project vs global)
            header: () => "Tenant admin",
            meta: {
              valueType: "boolean",
            },
            size: 20,
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
            meta: {
              valueType: "rights",
            },
            size: 25,
            filterFn: (data, columndId, filterValue) => {
              const right = data.getValue(columndId);
              if (filterValue === TLevel.Admin) {
                return right === TLevel.Admin || right === undefined;
              }
              return data && right === filterValue;
            },
          },
        ]}
        idAccessor={(u) => u.username}
      />
    </>
  );
}

function ProjectRightLevelModification(props: {
  username: string;
  name: string;
  level?: TLevel;
  cancel: () => void;
  submit: (old: any, obj: any) => Promise<any>;
}) {
  const { name, level, username, cancel, submit } = props;
  const [newLevel, setNewLevel] = useState(level);
  return (
    <div className="sub_container">
      <h3>
        Right level on project {name} for {username}
      </h3>
      <Select
        menuPlacement="top"
        styles={customStyles}
        defaultValue={{
          label: level ?? "No rights",
          value: newLevel ?? "No rights",
        }}
        options={["No rights", ...Object.values(TLevel)].map((v) => ({
          label: v,
          value: v,
        }))}
        onChange={(e) => {
          setNewLevel(e?.value as any);
        }}
      />
      <div className="d-flex justify-content-end">
        <button
          type="button"
          className="btn btn-danger m-2"
          onClick={() => cancel()}
        >
          Cancel
        </button>
        <button
          className="btn btn-primary m-2"
          onClick={() => submit({ username }, { level: newLevel })}
        >
          Save
        </button>
      </div>
    </div>
  );
}

function ProjectModification(props: {
  tenant: string;
  project: { name: string; description?: string };
  onDone: () => void;
}) {
  const { tenant, project } = props;
  const navigate = useNavigate();

  const updateMutation = useMutation({
    mutationFn: (data: { name: string; description: string }) =>
      updateProject(tenant, project.name, data),

    onSuccess: (data: any, variables: { name: string; description: string }) =>
      navigate(`/tenants/${tenant}/projects/${variables.name}/settings`),
  });
  return (
    <div className="sub_container">
      <Form
        schema={{
          name: {
            type: type.string,
            label: "Project name",
            defaultValue: project.name,
            required: true,
            props: {
              autoFocus: true,
            },
            constraints: [
              constraints.matches(
                PROJECT_NAME_REGEXP,
                `Project name must match regex ${PROJECT_NAME_REGEXP.toString()}`
              ),
            ],
          },
          description: {
            type: type.string,
            format: format.textarea,
            label: "Description",
            defaultValue: project.description,
          },
        }}
        onSubmit={(data: any) =>
          updateMutation.mutateAsync(data).then(() => props.onDone())
        }
        onClose={() => props.onDone()}
      />
    </div>
  );
}
