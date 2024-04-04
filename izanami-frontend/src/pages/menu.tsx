import * as React from "react";
import { useQuery } from "react-query";
import {
  MutationNames,
  projectsQueryKey,
  queryProjects,
  queryTenants,
} from "../utils/queries";
import { matchPath, NavLink, useLocation, useNavigate } from "react-router-dom";
import Select from "react-select";
import { customStyles } from "../styles/reactSelect";
import {
  findTenantRight,
  useAdmin,
  useProjectRight,
  useTenantRight,
} from "../securityContext";
import { TLevel } from "../utils/types";
import { IzanamiContext } from "../securityContext";
import { GlobalContextIcon } from "../utils/icons";

function hideSidebar() {
  let el = document.getElementById("btnToggler");
  if (el && window.getComputedStyle(el).display === "block") {
    document.getElementById("navbarToggler")?.classList.add("collapse");
    document.getElementById("navbarToggler")?.classList.remove("show");
  }
}

export function Menu(props: {
  location?: { pathname?: string };
  tenant?: string;
  project?: string;
}) {
  let { tenant, project } = props;
  const [selectedTenant, selectTenant] = React.useState<string | undefined>();
  const [selectedProject, selectProject] = React.useState<string | undefined>();
  const tenantQuery = useQuery(MutationNames.TENANTS, () => queryTenants());
  const navigate = useNavigate();
  const isAdmin = useAdmin();
  let isTenantAdmin = useTenantRight(tenant, TLevel.Admin);
  const isProjectAdmin = useProjectRight(tenant, project, TLevel.Admin);
  const { user } = React.useContext(IzanamiContext);
  const projectsQuery = useQuery(projectsQueryKey(tenant!), () =>
    queryProjects(tenant!)
  );

  if (tenantQuery.isSuccess) {
    // Allow to keep tenant menu part while in settings / users views
    if (!tenant && user) {
      tenant =
        selectedTenant ?? user.defaultTenant ?? tenantQuery.data?.[0]?.name;
      isTenantAdmin =
        isAdmin || findTenantRight(user?.rights, tenant) === TLevel.Admin;
    }

    if (projectsQuery.isSuccess) {
      project = project ?? projectsQuery.data?.[0]?.name ?? selectedProject;
    }
    return (
      <>
        <ul className="nav flex-column">
          {tenant && (
            <>
              <li>
                {tenantQuery.data.length === 1 ? (
                  <p style={{ color: "#e6e6e6" }}>
                    <i className="fas fa-cloud" aria-hidden></i> Tenant {tenant}
                  </p>
                ) : (
                  <>
                    <h3>
                      <i className="fas fa-cloud" aria-hidden></i> Tenant
                    </h3>
                    <div>
                      <Select
                        options={tenantQuery.data.map((t) => ({
                          value: t.name,
                          label: t.name,
                        }))}
                        styles={customStyles}
                        value={{ value: tenant, label: tenant }}
                        onChange={(v) => {
                          selectTenant(v!.value);
                          navigate(`/tenants/${v!.value}`);
                        }}
                      />
                    </div>
                  </>
                )}
              </li>
              <li>
                {projectsQuery.data?.length === 1 ? (
                  <>
                    <NavLink
                      to={`/tenants/${tenant}/projects/${project}`}
                      className={() => ""}
                      onClick={() => hideSidebar()}
                    >
                      <i className="ms-2 fas fa-building" aria-hidden></i>
                      Project {project}
                    </NavLink>
                  </>
                ) : (
                  <>
                    <h3 style={{ marginTop: "10px" }}>
                      <i className="ms-2 fas fa-building" aria-hidden></i>
                      Projects
                    </h3>
                    <div>
                      <Select
                        options={projectsQuery.data?.map((t) => ({
                          value: t.name,
                          label: t.name,
                        }))}
                        styles={customStyles}
                        value={{
                          value: project,
                          label: project,
                        }}
                        onChange={(v) => {
                          selectProject(v!.value);
                          navigate(`/tenants/${tenant}/projects/${v!.value}`);
                        }}
                      />
                    </div>
                  </>
                )}
              </li>
              <li
                className={
                  matchPath(
                    { path: "/tenants/:tenant/projects/:project/*" },
                    props?.location?.pathname || ""
                  )
                    ? "active mt-2"
                    : "inactive mt-2"
                }
              >
                {matchPath(
                  { path: "/tenants/:tenant/projects/:project/*" },
                  props?.location?.pathname || ""
                ) ? (
                  <ul
                    className="nav flex-column"
                    style={{ marginLeft: "36px" }}
                  >
                    <li
                      className={
                        matchPath(
                          { path: "/tenants/:tenant/projects/:project" },
                          props?.location?.pathname || ""
                        )
                          ? "active"
                          : "inactive"
                      }
                    >
                      <NavLink
                        to={`/tenants/${tenant}/projects/${project}`}
                        className={() => ""}
                        onClick={() => hideSidebar()}
                      >
                        Features
                      </NavLink>
                    </li>
                    <li
                      className={
                        matchPath(
                          {
                            path: "/tenants/:tenant/projects/:project/contexts",
                          },
                          props?.location?.pathname || ""
                        )
                          ? "active"
                          : "inactive"
                      }
                    >
                      <NavLink
                        to={`/tenants/${tenant}/projects/${project}/contexts`}
                        className={() => ""}
                        onClick={() => hideSidebar()}
                      >
                        Contexts
                      </NavLink>
                    </li>
                    {isProjectAdmin && (
                      <li
                        className={
                          matchPath(
                            {
                              path: "/tenants/:tenant/projects/:project/settings",
                            },
                            props?.location?.pathname || ""
                          )
                            ? "active"
                            : "inactive"
                        }
                      >
                        <NavLink
                          to={`/tenants/${tenant}/projects/${project}/settings`}
                          className={() => ""}
                          onClick={() => hideSidebar()}
                          aria-label="Project settings"
                        >
                          Settings
                        </NavLink>
                      </li>
                    )}
                  </ul>
                ) : (
                  <></>
                )}
              </li>
              <li
                className={
                  matchPath(
                    { path: "/tenants/:tenant/keys" },
                    props?.location?.pathname || ""
                  )
                    ? "active"
                    : "inactive"
                }
              >
                <NavLink
                  to={`/tenants/${tenant}/keys`}
                  className={() => ""}
                  onClick={() => hideSidebar()}
                >
                  <i className="ms-2 fas fa-key" aria-hidden></i> Keys
                </NavLink>
              </li>
              <li
                className={
                  matchPath(
                    { path: "/tenants/:tenant/contexts" },
                    props?.location?.pathname || ""
                  )
                    ? "active"
                    : "inactive"
                }
              >
                <NavLink
                  to={`/tenants/${tenant}/contexts`}
                  className={() => ""}
                  onClick={() => hideSidebar()}
                >
                  <GlobalContextIcon className="mx-2" />
                  Global contexts
                </NavLink>
              </li>
              <li
                className={
                  matchPath(
                    { path: "/tenants/:tenant/tags" },
                    props?.location?.pathname || ""
                  )
                    ? "active"
                    : "inactive"
                }
              >
                <NavLink
                  to={`/tenants/${tenant}/tags`}
                  className={() => ""}
                  onClick={() => hideSidebar()}
                >
                  <i className="ms-2 fa-solid fa-tag" aria-hidden />
                  Tags
                </NavLink>
              </li>
              <li
                className={
                  matchPath(
                    { path: "/tenants/:tenant/scripts" },
                    props?.location?.pathname || ""
                  )
                    ? "active"
                    : "inactive"
                }
              >
                <NavLink
                  to={`/tenants/${tenant}/scripts`}
                  className={() => ""}
                  onClick={() => hideSidebar()}
                >
                  <i className="ms-2 fa-solid fa-code" aria-hidden />
                  WASM scripts
                </NavLink>
              </li>

              <li
                className={
                  matchPath(
                    { path: "/tenants/:tenant/query-builder" },
                    props?.location?.pathname || ""
                  )
                    ? "active"
                    : "inactive"
                }
              >
                <NavLink
                  to={`/tenants/${tenant}/query-builder`}
                  className={() => ""}
                  onClick={() => hideSidebar()}
                >
                  <i className="ms-2 fa-solid fa-hammer" aria-hidden></i> Query
                  builder
                </NavLink>
              </li>
              {isTenantAdmin && (
                <li
                  className={
                    matchPath(
                      { path: "/tenants/:tenant/settings" },
                      props?.location?.pathname || ""
                    )
                      ? "active"
                      : "inactive"
                  }
                >
                  <NavLink
                    to={`/tenants/${tenant}/settings`}
                    className={() => ""}
                    onClick={() => hideSidebar()}
                  >
                    <i className="ms-2 fas fa-cog" aria-hidden></i> Settings
                  </NavLink>
                </li>
              )}
            </>
          )}
        </ul>
        {isAdmin && (
          <ul className="nav flex-column">
            <hr />
            <h3>Admin zone</h3>
            <li
              className={
                matchPath({ path: "/users" }, props?.location?.pathname || "")
                  ? "active"
                  : "inactive"
              }
            >
              <NavLink to={`/users`} onClick={() => hideSidebar()}>
                <i className="ms-2 fas fa-user" aria-hidden></i> Users
              </NavLink>
            </li>
            <li
              className={
                matchPath(
                  { path: "/settings" },
                  props?.location?.pathname || ""
                )
                  ? "active"
                  : "inactive"
              }
            >
              <NavLink to={`/settings`} onClick={() => hideSidebar()}>
                <i className="ms-2 fas fa-cog" aria-hidden></i> Global settings
              </NavLink>
            </li>
          </ul>
        )}
      </>
    );
  } else if (tenantQuery.isLoading) {
    return <div>Loading...</div>;
  } else {
    return <div>Error while fetching tenants</div>;
  }
}
