import * as React from "react";
import { useMutation, useQuery } from "react-query";
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
import { IzanamiContext, useProjectRight } from "../securityContext";
import queryClient from "../queryClient";
import { Form, constraints, format, type } from "@maif/react-forms";
import { useState } from "react";
import { TLevel } from "../utils/types";
import { GenericTable } from "../components/GenericTable";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import { InvitationForm } from "../components/InvitationForm";
import { PROJECT_NAME_REGEXP } from "../utils/patterns";
import { Loader } from "../components/Loader";

export function ProjectSettings(props: { project: string; tenant: string }) {
  const { project, tenant } = props;
  const queryKey = projectQueryKey(tenant, project);
  const projectQuery = useQuery(queryKey, () => queryProject(tenant, project));

  const [modification, setModification] = useState(false);
  const { askConfirmation } = React.useContext(IzanamiContext);
  const navigate = useNavigate();

  const projectDelete = useMutation(
    (data: { tenant: string; project: string }) => {
      const { tenant, project } = data;
      return deleteProject(tenant, project);
    }
  );

  const inviteUsers = useMutation(
    (data: { users: string[]; level: TLevel }) => {
      const { users, level } = data;
      return inviteUsersToProject(tenant, project, users, level);
    },
    {
      onSuccess: () => {
        queryClient.invalidateQueries(projectUserQueryKey(tenant, project));
      },
    }
  );
  const [inviting, setInviting] = useState(false);

  if (projectQuery.isError) {
    return <div>Failed to fetch project / project users</div>;
  } else if (projectQuery.data) {
    return (
      <>
        <h1>Settings</h1>
        <h2 className="mt-5">
          Project users{" "}
          <button
            type="button"
            className="btn btn-primary btn-sm mb-2 ms-3"
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
          />
        )}
        <ProjectUsers tenant={tenant} project={project} />
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
                askConfirmation(
                  <>
                    Are you sure you wan't to delete project {project} ?
                    <br />
                    All features will be deleted, this cannot be undone.
                  </>,
                  () =>
                    projectDelete
                      .mutateAsync({ project, tenant })
                      .then(() => navigate(`/tenants/${tenant}`))
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
    return <Loader message="Loading project / project users..." />;
  }
}

function ProjectUsers(props: { tenant: string; project: string }) {
  const { tenant, project } = props;
  const userQuery = useQuery(projectUserQueryKey(tenant, project), () =>
    queryProjectUsers(tenant, project)
  );

  const userUpdateMutationForProject = useMutation(
    (user: { username: string; right?: TLevel }) => {
      const { username, right } = user;
      return updateUserRightsForProject(username, tenant, project, right);
    }
  );
  const isProjectAdmin = useProjectRight(tenant, project, TLevel.Admin);

  if (userQuery.isLoading) {
    return <Loader message="Loading users..." />;
  } else if (userQuery.data) {
    return (
      <>
        <GenericTable
          data={userQuery.data}
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
                  submit={(newItem, oldItem) => {
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
                          queryClient.invalidateQueries(
                            projectUserQueryKey(tenant, project)
                          );
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
              header: () => "User Type",
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
  } else {
    return <div>Failed to load tenant users</div>;
  }
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
          className="btn btn-success m-2"
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

  const updateMutation = useMutation(
    (data: { name: string; description: string }) =>
      updateProject(tenant, project.name, data),
    {
      onSuccess: (
        data: any,
        variables: { name: string; description: string }
      ) => navigate(`/tenants/${tenant}/projects/${variables.name}/settings`),
    }
  );
  return (
    <div className="sub_container">
      <Form
        schema={{
          name: {
            type: type.string,
            label: "Project name",
            defaultValue: project.name,
            props: {
              autoFocus: true,
            },
            constraints: [
              constraints.required("Project name is required"),
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
        footer={({ valid }: { valid: () => void }) => {
          return (
            <>
              <div
                style={{ color: "red", fontSize: "20px" }}
                className="d-flex justify-content-end mt-3"
              >
                <i className="bi bi-exclamation-triangle"></i>&nbsp;Changing
                project name may break client URLs, for more details&nbsp;
                <a href="#">check the documentation</a>
              </div>
              <div className="d-flex justify-content-end">
                <button
                  type="button"
                  className="btn btn-danger m-2"
                  onClick={() => props.onDone()}
                >
                  Cancel
                </button>
                <button className="btn btn-success m-2" onClick={valid}>
                  Save
                </button>
              </div>
            </>
          );
        }}
      />
    </div>
  );
}
