import React, {
  Component,
  ComponentClass,
  FunctionComponent,
  useContext,
  useEffect,
} from "react";

import {
  createBrowserRouter,
  useSearchParams,
  useParams,
  useLocation,
  useNavigate,
  redirect,
  RouterProvider,
  Outlet,
  NavLink,
  Navigate,
} from "react-router-dom";
import { Toaster } from "react-hot-toast";

import { Project } from "./pages/project";
import { Menu } from "./pages/menu";
import { Tenant } from "./pages/tenant";
import { QueryClientProvider, useQuery } from "react-query";
import queryClient from "./queryClient";
import { Tag } from "./pages/tag";
import { Login } from "./pages/login";
import Keys from "./pages/keys";
import { isAuthenticated } from "./utils/authUtils";
import "./App.css";
import { Configuration, TUser } from "./utils/types";
import { TIzanamiContext, IzanamiContext } from "./securityContext";
import { Topbar } from "./Topbar";
import { Users } from "./pages/users";
import { Settings } from "./pages/settings";
import { Invitation } from "./pages/invitation";
import { Profile } from "./pages/profile";
import { ForgottenPassword } from "./pages/forgottenPassword";
import { FormReset } from "./pages/formReset";
import { Modal } from "./components/Modal";
import {
  MutationNames,
  queryConfiguration,
  queryStats,
  queryTenants,
  updateConfiguration,
} from "./utils/queries";
import { TenantSettings } from "./pages/tenantSettings";
import { HomePage } from "./pages/home";
import { ProjectContexts } from "./pages/projectContexts";
import { ProjectSettings } from "./pages/projectSettings";
import { GlobalContexts } from "./pages/globalContexts";
import { QueryBuilder } from "./pages/queryBuilder";
import { GlobalContextIcon } from "./utils/icons";
import { WasmScripts } from "./pages/wasmScripts";
import {
  differenceInDays,
  differenceInMonths,
  differenceInSeconds,
} from "date-fns";
import { JsonViewer } from "@textea/json-viewer";

function Wrapper({
  element,
  ...rest
}: {
  element: FunctionComponent<any> | ComponentClass<any, any>;
}) {
  const params = useParams();
  const searchParams = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  return React.createElement(element, {
    ...rest,
    ...params,
    ...Object.fromEntries(searchParams[0]),
    location,
    navigate,
  });
}

function redirectToLoginIfNotAuthenticated({
  request,
}: {
  request: { url: string };
}) {
  const { pathname, search } = new URL(request.url);
  if (!isAuthenticated()) {
    return redirect(`/login?req=${encodeURI(`${pathname}${search}`)}`);
  }
}

const router = createBrowserRouter([
  {
    path: "/login",
    element: <Wrapper element={Login} />,
  },
  {
    path: "/password/_reset",
    element: <Wrapper element={FormReset} />,
  },
  {
    path: "/forgotten-password",
    element: <Wrapper element={ForgottenPassword} />,
  },
  {
    path: "/invitation",
    element: <Wrapper element={Invitation} />,
  },
  {
    path: "/",
    element: <Layout />,
    loader: redirectToLoginIfNotAuthenticated,
    handle: {
      crumb: () => (
        <NavLink className={() => ""} to={"/home"}>
          Home
        </NavLink>
      ),
    },
    children: [
      {
        path: "/",
        element: <RedirectToFirstTenant />,
      },
      {
        path: "/home",
        element: <Wrapper element={HomePage} />,
      },
      {
        path: "/users",
        element: <Wrapper element={Users} />,
        handle: {
          crumb: () => (
            <NavLink className={() => ""} to={`/users`}>
              <i className="fa fa-user" aria-hidden></i> Users
            </NavLink>
          ),
        },
      },
      {
        path: "/settings",
        element: <Wrapper element={Settings} />,
        handle: {
          crumb: () => (
            <NavLink className={() => ""} to={`/settings`}>
              <i className="fa fa-cog"></i> Settings
            </NavLink>
          ),
        },
      },
      {
        path: "/profile",
        element: <Wrapper element={Profile} />,
        handle: {
          crumb: () => (
            <NavLink className={() => ""} to={`/profile`}>
              Profile
            </NavLink>
          ),
        },
      },
      {
        path: "/tenants/:tenant/",
        handle: {
          crumb: (data: any) => (
            <NavLink className={() => ""} to={`/tenants/${data.tenant}`}>
              <i className="fas fa-cloud" aria-hidden></i>&nbsp;{data.tenant}
            </NavLink>
          ),
        },
        children: [
          {
            path: "/tenants/:tenant/contexts",
            element: <Wrapper element={GlobalContexts} />,
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/contexts`}
                >
                  <GlobalContextIcon />
                  &nbsp;Global contexts
                </NavLink>
              ),
            },
          },
          {
            path: "/tenants/:tenant/projects/:project",
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/projects/${data.project}`}
                >
                  <i className="fas fa-building" aria-hidden></i>&nbsp;
                  {data.project}
                </NavLink>
              ),
            },
            children: [
              {
                path: "/tenants/:tenant/projects/:project/",
                element: <Wrapper element={Project} />,
              },
              {
                path: "/tenants/:tenant/projects/:project/contexts",
                element: <Wrapper element={ProjectContexts} />,
                handle: {
                  crumb: (data: any) => (
                    <NavLink
                      className={() => ""}
                      to={`/tenants/${data.tenant}/projects/${data.project}/contexts`}
                    >
                      <i className="fa-solid fa-filter" aria-hidden></i>
                      &nbsp;Contexts
                    </NavLink>
                  ),
                },
              },
              {
                path: "/tenants/:tenant/projects/:project/settings",
                element: <Wrapper element={ProjectSettings} />,
                handle: {
                  crumb: (data: any) => (
                    <NavLink
                      className={() => ""}
                      to={`/tenants/${data.tenant}/projects/${data.project}/settings`}
                    >
                      <i className="fas fa-cog" aria-hidden></i>&nbsp;Settings
                    </NavLink>
                  ),
                },
              },
            ],
          },
          {
            path: "/tenants/:tenant/keys",
            element: <Wrapper element={Keys} />,
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/keys`}
                >
                  <i className="fas fa-key" aria-hidden></i>&nbsp;{data.project}
                  Keys
                </NavLink>
              ),
            },
          },
          {
            path: "/tenants/:tenant/settings",
            element: <Wrapper element={TenantSettings} />,
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/settings`}
                >
                  <i className="fas fa-cog" aria-hidden></i> Settings
                </NavLink>
              ),
            },
          },
          {
            path: "/tenants/:tenant/query-builder",
            element: <Wrapper element={QueryBuilder} />,
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/query-builder`}
                >
                  <i className="fa-solid fa-hammer" aria-hidden></i> Query
                  builder
                </NavLink>
              ),
            },
          },
          {
            path: "/tenants/:tenant/scripts",
            element: <Wrapper element={WasmScripts} />,
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/scripts`}
                >
                  <i className="fa-solid fa-code" aria-hidden></i> Wasm scripts
                </NavLink>
              ),
            },
          },
          {
            path: "/tenants/:tenant/",
            element: <Wrapper element={Tenant} />,
          },
          {
            path: "/tenants/:tenant/tags/:tag",
            element: <Wrapper element={Tag} />,
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/tags/${data.tag}`}
                >
                  # {data.tag}
                </NavLink>
              ),
            },
          },
        ],
      },
    ],
  },
  {
    path: "*",
    element: (
      <main style={{ padding: "1rem" }}>
        <p>There&apos;s nothing here!</p>
      </main>
    ),
  },
]);

function RedirectToFirstTenant(): JSX.Element {
  const context = useContext(IzanamiContext);
  const defaultTenant = context.user?.defaultTenant;
  let tenant = defaultTenant || context.user?.rights?.tenants?.[0];
  const tenantQuery = useQuery(MutationNames.TENANTS, () => queryTenants());

  if (tenant) {
    return <Navigate to={`/tenants/${tenant}`} />;
  } else if (tenantQuery.data && tenantQuery.data.length > 0) {
    return <Navigate to={`/tenants/${tenantQuery.data[0].name}`} />;
  } else if (tenantQuery.isLoading) {
    return <div>Loading...</div>;
  } else {
    return <Navigate to="/home" />;
  }
}

function Layout() {
  const { user, setUser, logout, expositionUrl, setExpositionUrl } =
    useContext(IzanamiContext);
  const loading = !user?.username || !expositionUrl;
  useEffect(() => {
    if (!user?.username) {
      fetch("/api/admin/users/rights")
        .then((response) => response.json())
        .then((user) => setUser(user))
        .catch(console.error);
    }
    if (!expositionUrl) {
      fetch("/api/admin/exposition")
        .then((response) => response.json())
        .then(({ url }) => setExpositionUrl(url));
    }
  }, [user?.username]);

  if (loading) {
    return <div className="container-fluid">Loading...</div>;
  }

  return (
    <div className="container-fluid">
      {/*TOOD externalsier la navbar*/}
      <div className="row">
        <nav className="navbar navbar-expand-lg fixed-top p-0">
          <div className="navbar-header justify-content-between justify-content-lg-center col-12 col-lg-2 d-flex px-3">
            <NavLink className="navbar-brand" to="/">
              <div className="d-flex flex-column justify-content-center align-items-center">
                Izanami
              </div>
            </NavLink>
            <button
              id="btnToggler"
              className="navbar-toggler collapsed"
              type="button"
              data-bs-toggle="collapse"
              data-bs-target="#navbarToggler"
              aria-controls="navbarToggler"
              aria-expanded="false"
              aria-label="Toggle navigation"
            >
              <span className="navbar-toggler-icon"></span>
            </button>
          </div>
          <ul className="navbar-nav ms-auto">
            <li className="nav-item dropdown userManagement me-2">
              <a
                className="nav-link"
                href="#"
                id="navbarDarkDropdownMenuLink"
                data-toggle="dropdown"
                role="button"
                aria-expanded="false"
                data-bs-toggle="dropdown"
              >
                {user?.username}
                <i className="bi bi-caret-down-fill" aria-hidden="true" />
              </a>
              <ul
                className="dropdown-menu dropdown-menu-right"
                aria-labelledby="navbarDarkDropdownMenuLink"
              >
                <li className="dropdown-item">
                  <NavLink
                    className={() => ""}
                    style={{ display: "block" }}
                    to={`/profile`}
                  >
                    <i className="fas fa-user me-2" aria-hidden />
                    Profile
                  </NavLink>
                </li>
                <li className="dropdown-item">
                  <a href="#" style={{ display: "block" }} onClick={logout}>
                    <i className="fas fa-power-off me-2" aria-hidden />
                    Logout
                  </a>
                </li>
              </ul>
            </li>
          </ul>
        </nav>
      </div>
      <div className="row">
        <div id="navbarToggler" className="navbar-collapse collapse">
          <aside className="col-lg-2 sidebar d-flex flex-column justify-content-between">
            <Wrapper element={Menu} />
          </aside>
        </div>
        <main className="col-lg-10 offset-lg-2 main">
          <header>
            <Wrapper element={Topbar} />
          </header>
          <Toaster
            toastOptions={{
              className: "toast-error",
            }}
          />
          <Outlet />
        </main>
      </div>
    </div>
  );
}

export class App extends Component {
  state: TIzanamiContext & {
    confirmation?: {
      message: JSX.Element | JSX.Element[];
      callback?: () => Promise<any>;
      onCancel?: () => Promise<any>;
      closeButtonText?: string;
      confirmButtonText?: string;
    };
  };
  constructor(props: any) {
    super(props);

    this.state = {
      user: undefined,
      setUser: (user: TUser) => {
        this.setState({ user: user });
        if (user.admin) {
          Promise.all([queryConfiguration(), queryStats()]).then(
            ([configuration, stats]) => {
              if (
                !configuration.anonymousReporting &&
                (!configuration.anonymousReportingLastAsked ||
                  differenceInMonths(
                    new Date(),
                    configuration.anonymousReportingLastAsked
                  ) > 3)
              ) {
                return this.state.askConfirmation(
                  <>
                    As you may know, Izanami is an open-source project. As such,
                    we don't have much feedback from our users. But this
                    feedback is essential for us to shape the future of Izanami.
                    <br />
                    The best way to help is to enable anonymous reporting . This
                    feature allow Izanami to send us periodical reports. It
                    won't send sensitive or personnal data, just a bunch of
                    statistics about your usage of izanami (see what's sent
                    below).
                    <br /> At any moment, you can turn off anonymous reporting
                    from settings page. Thanks for helping us building better
                    products !
                    <br />
                    <br />
                    <JsonViewer
                      rootName={false}
                      value={stats}
                      displayDataTypes={false}
                      displaySize={false}
                      defaultInspectDepth={0}
                      theme="dark"
                    />
                  </>,
                  () => {
                    return updateConfiguration({
                      ...configuration,
                      anonymousReporting: true,
                      anonymousReportingLastAsked: new Date(),
                    });
                  },
                  () => {
                    return updateConfiguration({
                      ...configuration,
                      anonymousReportingLastAsked: new Date(),
                    });
                  },
                  "Keep reporting disabled",
                  "Enable anonymous reporting"
                );
              }
            }
          );
        }
      },
      logout: () => {
        fetch("/api/admin/logout", { method: "POST" })
          .catch(() => {
            document.cookie =
              "token=; Path=/; Expires=Thu, 01 Jan 1970 00:00:01 GMT;SameSite=Strict;";
          })
          .finally(() => {
            window.location.href = "/login";
          });
      },
      refreshUser: () => {
        fetch("/api/admin/users/rights")
          .then((response) => response.json())
          .then((user) => this.state.setUser(user));
      },
      confirmation: undefined,
      askConfirmation: (
        msg: JSX.Element | JSX.Element[] | string,
        callback?: () => Promise<any>,
        onCancel?: () => Promise<any>,
        closeButtonText?: string,
        confirmButtonText?: string
      ) => {
        this.setState({
          confirmation: {
            message: msg,
            callback: callback,
            onCancel: onCancel,
            closeButtonText,
            confirmButtonText,
          },
        });
      },
      setExpositionUrl: (url) => {
        this.setState({ expositionUrl: url });
      },
      integrations: undefined,
    };
  }

  fetchIntegrationsIfNeeded(): void {
    if (!this.state.integrations) {
      fetch("/api/admin/integrations")
        .then((response) => response.json())
        .then((integrations) => this.setState({ integrations }));
    }
  }

  componentDidMount(): void {
    this.fetchIntegrationsIfNeeded();
  }

  componentDidUpdate(): void {
    this.fetchIntegrationsIfNeeded();
  }

  render() {
    const callback = this.state.confirmation?.callback;
    const modalProps = {
      visible: this.state.confirmation ? true : false,
      onClose: () => {
        this.state.confirmation?.onCancel?.();
        this.setState({ confirmation: undefined });
      },
      ...(callback
        ? {
            onConfirm: () => {
              if (callback) {
                callback().then(() =>
                  this.setState({ confirmation: undefined })
                );
              } else {
                this.setState({ confirmation: undefined });
              }
            },
          }
        : {}),
      closeButtonText: this.state.confirmation?.closeButtonText,
      confirmButtonText: this.state.confirmation?.confirmButtonText,
    };
    return (
      <>
        <IzanamiContext.Provider value={this.state}>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
            <Modal {...modalProps}>
              {this.state.confirmation ? this.state?.confirmation!.message : ""}
            </Modal>
          </QueryClientProvider>
        </IzanamiContext.Provider>
      </>
    );
  }
}
