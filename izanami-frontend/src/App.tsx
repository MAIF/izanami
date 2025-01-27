import React, {
  Component,
  ComponentClass,
  FunctionComponent,
  useContext,
  useEffect,
  useState,
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
import { QueryClientProvider, useQuery } from "@tanstack/react-query";
import queryClient from "./queryClient";
import { Tag } from "./pages/tag";
import { Login } from "./pages/login";
import Keys from "./pages/keys";
import { isAuthenticated } from "./utils/authUtils";
import "./App.css";
import { TUser } from "./utils/types";
import {
  TIzanamiContext,
  IzanamiContext,
  Modes,
  MODE_KEY,
  ModeValue,
} from "./securityContext";
import { Topbar } from "./Topbar";
import { Users } from "./pages/users";
import { Settings } from "./pages/settings";
import { Invitation } from "./pages/invitation";
import { Profile } from "./pages/profile";
import { ForgottenPassword } from "./pages/forgottenPassword";
import { FormReset } from "./pages/formReset";
import { Modal, RawModal } from "./components/Modal";
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
import { differenceInMonths } from "date-fns";
import { JsonViewer } from "@textea/json-viewer";
import { AtomicDesign } from "./pages/atomicDesign";
import { Swagger } from "./pages/swagger";
import { Tags } from "./pages/tags";
import { Loader } from "./components/Loader";
import Logo from "../izanami.png";
import { SearchModal } from "./components/SearchComponant/SearchModal";
import { WebHooks } from "./pages/webhooks";
import { PasswordModal } from "./components/PasswordModal";
import { ProjectLogs } from "./pages/projectLogs";

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
    path: "/swagger",
    element: <Wrapper element={Swagger} />,
  },
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
        path: "/atomicDesign",
        element: <Wrapper element={AtomicDesign} />,
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
              <i className="fa fa-user"></i> Profile
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
            path: "/tenants/:tenant/webhooks",
            element: <Wrapper element={WebHooks} />,
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/webhooks`}
                >
                  <i className="fas fa-plug" aria-hidden></i>&nbsp;Webhooks
                </NavLink>
              ),
            },
          },
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
                path: "/tenants/:tenant/projects/:project/logs",
                element: <Wrapper element={ProjectLogs} />,
                handle: {
                  crumb: (data: any) => (
                    <NavLink
                      className={() => ""}
                      to={`/tenants/${data.tenant}/projects/${data.project}/logs`}
                    >
                      <i className="fa-solid fa-timeline" aria-hidden></i>
                      &nbsp;Logs
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
            path: "/tenants/:tenant/tags",
            handle: {
              crumb: (data: any) => (
                <NavLink
                  className={() => ""}
                  to={`/tenants/${data.tenant}/tags`}
                >
                  <i className="fa-solid fa-tag" aria-hidden></i> Tags
                </NavLink>
              ),
            },
            children: [
              {
                path: "/tenants/:tenant/tags/",
                element: <Wrapper element={Tags} />,
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
                      <i className="fa-solid fa-tag" aria-hidden></i> {data.tag}
                    </NavLink>
                  ),
                },
              },
            ],
          },
          {
            path: "/tenants/:tenant/",
            element: <Wrapper element={Tenant} />,
          },
        ],
      },
    ],
  },
  {
    path: "*",
    element: (
      <div className="d-flex flex-column justify-content-center align-items-center">
        <img
          alt="Logo Izanami"
          src={Logo}
          style={{
            marginBottom: 48,
            height: 300,
          }}
        />
        <h3>Page not found</h3>
        <p>The page you are looking for doesn't exist.</p>
      </div>
    ),
  },
]);

function RedirectToFirstTenant(): JSX.Element {
  const context = useContext(IzanamiContext);
  const defaultTenant = context.user?.defaultTenant;
  let tenant = defaultTenant || context.user?.rights?.tenants?.[0];
  const tenantQuery = useQuery({
    queryKey: [MutationNames.TENANTS],
    queryFn: () => queryTenants(),
  });

  if (tenant) {
    return <Navigate to={`/tenants/${tenant}`} />;
  } else if (tenantQuery.data && tenantQuery.data.length > 0) {
    return <Navigate to={`/tenants/${tenantQuery.data[0].name}`} />;
  } else if (tenantQuery.isLoading) {
    return <Loader message="Loading tenants..." />;
  } else {
    return <Navigate to="/home" />;
  }
}

type AppLoadingState = "Loading" | "Error" | "Success";

function Layout() {
  const {
    user,
    setUser,
    logout,
    expositionUrl,
    version,
    updateLightMode,
    mode,
  } = useContext(IzanamiContext);
  const [isOpenModal, setIsOpenModal] = useState(false);
  const [loadingState, setLoadingState] = React.useState<AppLoadingState>(
    !user?.username || !expositionUrl ? "Loading" : "Success"
  );
  let { tenant } = useParams();
  const searchParamsTenant = useSearchParams()[0].get("tenant");
  tenant = searchParamsTenant || tenant;

  useEffect(() => {
    if (!user?.username) {
      setLoadingState("Loading");
      fetch("/api/admin/users/rights")
        .then((response) => {
          if (response.status === 401) {
            logout();
          } else if (response.status > 400) {
            throw new Error(
              `Failed to fetch rights, something is wrong with Izanami backend (status code ${response.status})`
            );
          } else {
            return response.json();
          }
        })
        .then((user) => {
          setLoadingState("Success");
          setUser(user);
        })
        .catch((error) => {
          setLoadingState("Error");
          console.error(error);
        });
    }
  }, [user?.username]);

  switch (loadingState) {
    case "Loading":
      return <Loader message="Loading..." />;
    case "Error":
      return (
        <div
          className="text-danger"
          style={{
            width: "100%",
            height: "30vh",
            display: "flex",
            justifyContent: "center",
            alignItems: "end",
            fontSize: "1.4rem",
            textAlign: "center",
          }}
        >
          Something went wrong while loading Izanami.
          <br /> Please check browser / backend logs
        </div>
      );
    case "Success":
      return (
        <div className="container-fluid">
          {/*TOOD externalsier la navbar*/}
          <div className="row">
            <nav className="navbar navbar-expand-lg fixed-top p-0">
              <div className="navbar-header justify-content-between justify-content-lg-center col-12 col-lg-2 d-flex px-3">
                <NavLink className="navbar-brand w-100" to="/home">
                  <div className="d-flex flex-column justify-content-center align-items-center  w-100">
                    Izanami
                    <span style={{ fontSize: "0.7rem" }}>{version}</span>
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
                {(tenant ||
                  user?.admin ||
                  Object.keys(user?.rights.tenants || {}).length > 0) && (
                  <li className="me-2 d-flex align-items-center justify-content-end my-1">
                    <button
                      className="btn btn-secondary"
                      id="btnSearch"
                      type="button"
                      onClick={() => setIsOpenModal(true)}
                      style={{ opacity: "0.7" }}
                    >
                      <span className="fa fa-search"></span>
                      <span className="text-searchbutton d-none d-md-inline">
                        &nbsp;Click to search
                      </span>
                    </button>
                  </li>
                )}
                <li
                  onClick={() =>
                    updateLightMode(
                      mode === Modes.dark ? Modes.light : Modes.dark
                    )
                  }
                  className="me-2 d-flex align-items-center justify-content-end my-2"
                >
                  <i
                    id="lightMode"
                    className={`${
                      mode === Modes.light ? "fa fa-lightbulb" : "fa fa-moon"
                    }`}
                    style={{ color: "var(--color_level2)", cursor: "pointer" }}
                  />
                </li>
                <li className="nav-item dropdown d-flex align-items-center userManagement me-2">
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
                    <li>
                      <NavLink className="dropdown-item" to={`/profile`}>
                        <i className="fas fa-user me-2" aria-hidden />
                        Profile
                      </NavLink>
                    </li>
                    <li>
                      <a href="#" className="dropdown-item" onClick={logout}>
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
          {(tenant ||
            user?.admin ||
            Object.keys(user?.rights.tenants || {}).length > 0) && (
            <SearchModal
              currentTenant={tenant}
              availableTenants={Object.keys(user?.rights.tenants || {})}
              isOpenModal={isOpenModal}
              onClose={() => setIsOpenModal(false)}
            />
          )}
        </div>
      );
  }
}

function parseMode(modeStr: string) {
  return modeStr === Modes.light ? Modes.light : Modes.dark;
}

export class App extends Component {
  state: TIzanamiContext & {
    confirmation?: {
      message: JSX.Element | JSX.Element[] | React.FC;
      callback?: () => Promise<any>;
      onCancel?: () => Promise<any>;
      closeButtonText?: string;
      confirmButtonText?: string;
      noFooter?: boolean;
    };
    passwordConfirmation?: {
      message: JSX.Element | JSX.Element[] | string;
      callback: (password: string) => Promise<void>;
      title?: string;
    };
  };
  setupLightMode() {
    const mode = parseMode(window.localStorage.getItem(MODE_KEY) ?? "");
    this.updateLightMode(mode);
  }
  updateLightMode(mode: ModeValue) {
    window.localStorage.setItem(MODE_KEY, mode);

    this.setState({ mode: mode });
    window.document.body.classList.remove("white-mode");
    window.document.body.classList.remove("dark-mode");
    if (mode === Modes.dark) {
      window.document.body.classList.add("dark-mode");
      document.documentElement.setAttribute("data-theme", Modes.dark);
    } else {
      window.document.body.classList.add("white-mode");
      document.documentElement.setAttribute("data-theme", Modes.light);
    }
  }
  constructor(props: any) {
    super(props);
    this.state = {
      updateLightMode: (m) => this.updateLightMode(m),
      mode: Modes.dark,
      version: undefined,
      user: undefined,
      setUser: (user: TUser) => {
        this.setState({ user: user });
        if (user.admin) {
          queryConfiguration().then((configuration) => {
            this.setState({ version: configuration.version });
            if (
              !configuration.anonymousReporting &&
              (!configuration.anonymousReportingLastAsked ||
                differenceInMonths(
                  new Date(),
                  configuration.anonymousReportingLastAsked
                ) > 3)
            ) {
              return queryStats().then((stats) => {
                this.state.askConfirmation(
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
              });
            }
          });
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
        return new Promise((resolve) => {
          this.setState({
            confirmation: {
              message: msg,
              callback: callback
                ? () => callback().finally(() => resolve())
                : undefined,
              onCancel: () => {
                return onCancel?.().finally(() => resolve());
              },
              closeButtonText,
              confirmButtonText,
            },
          });
        });
      },
      displayModal: (
        Content: React.FC<{
          close: () => void;
        }>
      ) => {
        return new Promise((resolve) => {
          this.setState({
            confirmation: {
              noFooter: true,
              message: () => (
                <Content
                  close={() => {
                    this.setState({ confirmation: undefined });
                    resolve();
                  }}
                />
              ),
            },
          });
        });
      },
      askPasswordConfirmation: (
        message: JSX.Element | JSX.Element[] | string,
        onConfirm: (password: string) => Promise<void>,
        title?: string
      ) => {
        return new Promise((resolve) => {
          this.setState({
            passwordConfirmation: {
              message,
              callback: (password: string) =>
                onConfirm(password).finally(() => resolve()),
              title,
            },
          });
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

  fetchExpositionUrlIfNeeded(): void {
    if (!this.state.expositionUrl) {
      fetch("/api/admin/exposition")
        .then((response) => response.json())
        .then(({ url }) => this.setState({ expositionUrl: url }));
    }
  }

  componentDidMount(): void {
    this.fetchIntegrationsIfNeeded();
    this.fetchExpositionUrlIfNeeded();
    this.setupLightMode();
  }

  componentDidUpdate(): void {
    this.fetchIntegrationsIfNeeded();
    this.fetchExpositionUrlIfNeeded;
  }

  render() {
    const callback = this.state.confirmation?.callback;
    const modalProps = {
      visible: !!this.state.confirmation,
      noFooter: !!this.state?.confirmation?.noFooter,
      onClose: () => {
        this.state.confirmation?.onCancel?.();
        this.setState({
          confirmation: undefined,
        });
      },
      ...(callback
        ? {
            onConfirm: () => {
              callback().then(() => this.setState({ confirmation: undefined }));
            },
          }
        : {}),
      closeButtonText: this.state.confirmation?.closeButtonText,
      confirmButtonText: this.state.confirmation?.confirmButtonText,
    };
    const modalPasswordProps = {
      title: this.state.passwordConfirmation?.title,
      isOpenModal: !!this.state.passwordConfirmation,
      onClose: () => {
        this.setState({
          passwordConfirmation: undefined,
        });
      },
      onConfirm: (password: string) => {
        if (this.state.passwordConfirmation?.callback) {
          this.state.passwordConfirmation
            ?.callback(password)
            .then(() => this.setState({ passwordConfirmation: undefined }));
        } else {
          this.setState({ passwordConfirmation: undefined });
        }
      },
    };

    let modalPart = <></>;
    if (this.state.confirmation) {
      if (typeof this.state.confirmation?.message === "function") {
        const ModalChild = this.state.confirmation.message;
        modalPart = (
          <RawModal {...modalProps}>
            <ModalChild />
          </RawModal>
        );
      } else {
        modalPart = (
          <Modal {...modalProps}>{this.state?.confirmation!.message}</Modal>
        );
      }
    }

    return (
      <>
        <IzanamiContext.Provider value={this.state}>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
            {modalPart}
            {this.state.passwordConfirmation && (
              <PasswordModal {...modalPasswordProps}>
                {this.state.passwordConfirmation.message}
              </PasswordModal>
            )}
          </QueryClientProvider>
        </IzanamiContext.Provider>
      </>
    );
  }
}
