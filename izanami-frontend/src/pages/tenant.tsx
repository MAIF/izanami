import { Form, constraints, format, type } from "@maif/react-forms";
import * as React from "react";
import { useState } from "react";
import { useMutation, useQuery } from "react-query";
import { NavLink, useLocation, useNavigate } from "react-router-dom";
import queryClient from "../queryClient";
import { useTenantRight } from "../securityContext";
import { createProject, queryTenant, tenantQueryKey } from "../utils/queries";
import {
  ProjectInCreationType,
  TenantProjectType,
  TenantType,
  TLevel,
} from "../utils/types";
import { PROJECT_NAME_REGEXP } from "../utils/patterns";
import { Loader } from "../components/Loader";
import { CopyButton } from "../components/FeatureTable";

export function Tenant({ tenant }: { tenant: string }) {
  const queryKey = tenantQueryKey(tenant);
  const tenantQuery = useQuery(queryKey, () => queryTenant(tenant));

  if (tenantQuery.isSuccess) {
    const tenant = tenantQuery.data;
    return (
      <>
        <div className="container">
          <div className="row row-cols-1 row-cols-sm-2">
            <div className="col flex-grow-1">
              <ProjectList tenant={tenant} />
            </div>
          </div>
        </div>
      </>
    );
  } else if (tenantQuery.isLoading) {
    return <Loader message="Loading..." />;
  } else {
    return <div>Error while fetching tenant</div>;
  }
}

function ProjectList(props: { tenant: TenantType }) {
  const { tenant } = props;
  const location = useLocation();
  let defaultProjects = location?.state?.item
    ? [
        {
          id: location?.state?.item.id,
          name: location?.state?.item.name,
          description: location?.state?.item.description,
        },
      ]
    : [];
  const queryKey = tenantQueryKey(tenant.name);
  const [creating, setCreating] = useState<boolean>(false);
  const [selectedProject, selectProject] = useState<
    TenantProjectType[] | undefined
  >(defaultProjects);

  const projectCreationMutation = useMutation(
    (data: ProjectInCreationType) => createProject(tenant.name, data),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(queryKey);
      },
    }
  );

  const hasTenantWriteRight = useTenantRight(tenant.name, TLevel.Write);
  const navigate = useNavigate();

  const noProjects = tenant?.projects?.length === 0;
  return (
    <>
      <div className="d-flex align-items-center">
        <h1>Projects</h1>
        {hasTenantWriteRight && !noProjects && !creating && (
          <button
            type="button"
            className="btn btn-primary btn-sm mb-2 ms-3"
            onClick={() => setCreating(true)}
          >
            Create new project
          </button>
        )}
      </div>
      <div className="d-flex flex-column">
        <input
          defaultValue={location?.state?.item.name || ""}
          placeholder="Search project"
          onChange={(e) => {
            selectProject(
              tenant?.projects?.filter((f) =>
                f.name.toLowerCase().startsWith(e.target.value.toLowerCase())
              )
            );
          }}
          className="form-control"
          type="text"
          name="search-form-project"
          id="search-form-project"
        />
      </div>
      {noProjects && !creating && (
        <div className="item-block">
          <div className="item-text">
            This tenant does not have any project
            {hasTenantWriteRight ? " yet" : " you can see"}.
          </div>
          {hasTenantWriteRight && (
            <>
              <button
                type="button"
                className="btn btn-primary btn-lg"
                style={{
                  marginBottom: "12px",
                  width: "215px",
                }}
                onClick={() => setCreating(true)}
              >
                Create new project
              </button>
              <button
                type="button"
                className="btn btn-secondary btn-lg"
                style={{
                  width: "215px",
                }}
                onClick={() => navigate(`/tenants/${tenant.name}/settings`)}
              >
                Import data
              </button>
            </>
          )}
        </div>
      )}
      <div className="row row-cols-1 row-cols-sm-2 row-cols-md-3 g-3 nav mt-4">
        {creating && (
          <div className="col" key="new-project">
            <div className="card shadow-sm anim__popUp">
              <div className="card-body">
                <Form
                  schema={projectCreationSchema}
                  onSubmit={(project: any) => {
                    projectCreationMutation
                      .mutateAsync(project)
                      .then(() => setCreating(false))
                      .then(() => navigate(`projects/${project.name}`));
                  }}
                  footer={({ valid }: { valid: () => void }) => {
                    return (
                      <div className="d-flex justify-content-end pt-3">
                        <button
                          type="button"
                          className="btn btn-danger"
                          onClick={() => setCreating(false)}
                        >
                          Cancel
                        </button>
                        <button
                          className="btn btn-success ms-2"
                          onClick={valid}
                        >
                          Save
                        </button>
                      </div>
                    );
                  }}
                />
              </div>
            </div>
          </div>
        )}
        {selectedProject && selectedProject.length > 0
          ? selectedProject.map((selectedProject) => (
              <ProjectCard
                key={selectedProject.id}
                project={selectedProject}
                tenantName={tenant.name}
              />
            ))
          : tenant?.projects?.map((project) => (
              <ProjectCard
                key={project.id}
                project={project}
                tenantName={tenant.name}
              />
            ))}
      </div>
    </>
  );
}

function ProjectCard(props: {
  project: TenantProjectType;
  tenantName: string;
}) {
  const { project, tenantName } = props;
  return (
    <div className="col" key={project.name}>
      <div className="card shadow-sm">
        <div className="card-body position-relative card-project">
          <h2>
            <NavLink
              className={() => "card-title stretched-link"}
              to={`/tenants/${tenantName}/projects/${project.name}`}
            >
              {project.name}
            </NavLink>
          </h2>

          {project.description || ""}
        </div>
        <div className="d-flex mb-2 justify-content-end">
          <CopyButton value={project.id} title={"ID"} />
        </div>
      </div>
    </div>
  );
}
const projectCreationSchema = {
  name: {
    type: type.string,
    label: "Project name",
    props: {
      autoFocus: true,
    },
    constraints: [
      constraints.required("Project name is required"),
      constraints.matches(
        PROJECT_NAME_REGEXP,
        `Project name must match ${PROJECT_NAME_REGEXP.toString()}`
      ),
    ],
  },
  description: {
    type: type.string,
    format: format.textarea,
    label: "Description",
  },
};
