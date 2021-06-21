import React, { Component } from "react";
import {
  Router,
  Link,
  Redirect,
  Route,
  Switch,
  withRouter,
} from "react-router-dom";
import {
  ApikeyPage,
  ConfigExplorerPage,
  ConfigurationsPage,
  ExperimentsExplorerPage,
  ExperimentsPage,
  FeaturesExplorerPage,
  FeaturesPage,
  GlobalScriptsPage,
  HomePage,
  LoggersPage,
  LoginPage,
  NotFoundPage,
  UserPage,
  WebHooksPage,
} from "./pages";
import { SweetModal } from "./inputs/SweetModal";
import queryString from "query-string";
import isEmpty from "lodash/isEmpty";
import "../styles/main.scss";
import { MultiSearch } from "./inputs";
import { DynamicTitle } from "./components/DynamicTitle";
import { IzanamiEvents } from "./services/events";
import * as IzanamiServices from "./services";
import Cookies from "js-cookie";
const pictos = {
  configurations: "fas fa-wrench",
  features: "fas fa-toggle-on",
  experiments: "fas fa-flask",
  scripts: "far fa-file-alt",
  webhooks: "fas fa-plug",
};

export class LoggedApp extends Component {
  state = {
    version: "",
  };

  decorate = (Component, props) => {
    const newProps = { ...props };
    const query =
      queryString.parse((props.location || { search: "" }).search) || {};
    newProps.location.query = query;
    newProps.params = newProps.match.params || {};
    return (
      <Component
        setTitle={(t) => DynamicTitle.setContent(t)}
        getTitle={() => DynamicTitle.getContent()}
        user={this.props.user}
        userManagementMode={this.props.userManagementMode}
        confirmationDialog={this.props.confirmationDialog}
        setSidebarContent={(c) => DynamicSidebar.setContent(c)}
        {...newProps}
      />
    );
  };

  componentDidMount() {
    IzanamiEvents.start();
    IzanamiServices.appInfo().then((appInfo) => {
      this.setState({ version: appInfo.version });
    });
    this.props.history.listen(() => {
      $("#navbarSupportedContent").collapse("hide");
      $("#navbar").collapse("hide");
    });
  }

  componentWillUnmount() {
    IzanamiEvents.stop();
  }

  searchServicesOptions = ({ query, filters }) => {
    return IzanamiServices.search({ query, filters }).then((results) => {
      return results.map((v) => ({
        label: `${v.id}`,
        type: v.type,
        value: v.id,
      }));
    });
  };

  lineRenderer = (option) => {
    return (
      <label style={{ cursor: "pointer" }} className="justify-content-start">
        <i className={`me-2 ${pictos[option.type]}`} /> {option.label}
      </label>
    );
  };

  gotoService = (e) => {
    window.location.href = `/${e.type}/edit/${e.value}`;
  };

  gotoCreateUser = () => {
    window.location.href = "/users/add";
  };

  onChangemeClosed = () => {
    Cookies.remove("notifyuser");
  };

  render() {
    const pathname = window.location.pathname;
    const className = (part) => (part === pathname ? "active" : "inactive");

    const userManagementEnabled =
      this.props.userManagementMode !== "None" &&
      this.props.user &&
      this.props.user.admin;

    const apikeyManagementEnabled =
      this.props.enabledApikeyManagement &&
      this.props.user &&
      this.props.user.admin;

    const selected = (this.props.params || {}).lineId;

    const changeme = Cookies.get("notifyuser") || this.props.user.changeme;

    return (
      <div className="container-fluid">
        <nav className="navbar navbar-expand-lg fixed-top p-0">
          {/* <div className="container-fluid"> */}
            <div className="navbar-header justify-content-between justify-content-lg-center col-12 col-lg-2 d-flex px-3">
              <a className="navbar-brand" href="/">
                <div className="d-flex flex-column justify-content-center align-items-center">
                  <span>イザナミ</span> Izanami
                </div>
              </a>
              <button
              className="navbar-toggler menu" type="button" data-bs-toggle="collapse" data-bs-target="#navbarSupportedContent" aria-controls="navbarSupportedContent" aria-expanded="false" aria-label="Toggle navigation"
              >
                <span className="sr-only">Toggle sidebar</span>
                <span>Menu</span>
              </button>
            </div>

            <form className="ms-4 p-2 p-lg-0">
              {selected && (
                <div className="mb-3 mr-2 d-inline">
                  <span
                    title="Current line"
                    className="label label-success"
                    style={{ fontSize: 20, cursor: "pointer" }}
                  >
                    {selected}
                  </span>
                </div>
              )}
              <div>
                <MultiSearch
                  filters={[
                    { name: "features", label: "Features", active: true },
                    { name: "configs", label: "Configurations", active: true },
                    { name: "experiments", label: "Experiments", active: true },
                    { name: "scripts", label: "Scripts", active: true },
                  ]}
                  query={this.searchServicesOptions}
                  lineRenderer={this.lineRenderer}
                  onElementSelected={this.gotoService}
                />
              </div>
            </form>
            <ul key="admin-menu" className="navbar-nav ms-auto">
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
                  <i className="fas fa-cog" aria-hidden="true" />
                </a>
                <ul
                  className="dropdown-menu dropdown-menu-right"
                  aria-labelledby="navbarDarkDropdownMenuLink"
                >
                  {userManagementEnabled && (
                    <li key="li-users" className="dropdown-item">
                      <Link
                        to="/users"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        <i className="fas fa-user me-2" /> Users management
                      </Link>
                    </li>
                  )}
                  {apikeyManagementEnabled && (
                    <li key="li-apikeys" className="dropdown-item">
                      <Link
                        to="/apikeys"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        <i className="fas fa-key me-2" /> Api Keys management
                      </Link>
                    </li>
                  )}
                  <li className="dropdown-item">
                    <a href={this.props.logout} className="link-logout">
                      <i className="fas fa-power-off me-2" />{" "}
                      {this.props.user ? this.props.user.email : ""}
                    </a>
                  </li>
                  <li>
                    <div className="dropdown-divider" />
                  </li>
                  <li className="dropdown-item">
                    <a href="#">
                      <img
                        src={`${
                          window.__contextPath
                        }/assets/images/izanami-inverse.png`}
                        width="16"
                        className="me-2"
                      />{" "}
                      version {this.state.version}
                    </a>
                  </li>
                </ul>
              </li>
            </ul>
          {/* </div> */}
        </nav>

        <div className="container-fluid">
          <div className="row">
            <div className="col-lg-2 sidebar" id="navbarSupportedContent">
              <div className="sidebar-container">
                <div className="sidebar-content">
                  <ul className="nav nav-sidebar flex-column">
                    <li className={className("/")}>
                      <h3>
                        <Link to="/">
                          <i className="fas fa-tachometer-alt" /> Home
                        </Link>
                      </h3>
                    </li>
                    <li className={className("/features")}>
                      <Link to="/features" style={{ cursor: "pointer" }}>
                        <i className="fas fa-toggle-on" />
                        Features
                      </Link>
                    </li>
                    <li className={className("/configurations")}>
                      <Link
                        to="/configurations"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        <i className="fas fa-wrench" />
                        Configurations
                      </Link>
                    </li>
                    <li className={className("/experiments")}>
                      <Link
                        to="/experiments"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        <i className="fas fa-flask" />
                        Experiments
                      </Link>
                    </li>
                    <li className={className("/scripts")}>
                      <Link
                        to="/scripts"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        <i className="far fa-file-alt" />
                        Global Scripts
                      </Link>
                    </li>
                    <li className={className("/webhooks")}>
                      <Link
                        to="/webhooks"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        <i className="fas fa-plug" />
                        WebHooks
                      </Link>
                    </li>
                  </ul>
                  <ul className="nav nav-sidebar  flex-column">
                    <li className={className("/")}>
                      <h3 style={{ marginTop: 0 }}>
                        <i className="fas fa-tachometer-alt" /> Explore
                      </h3>
                    </li>
                    <li className={className("/explorer/features")}>
                      <Link
                        to="/explorer/features"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        Features Explorer
                      </Link>
                    </li>
                    <li className={className("/explorer/configs")}>
                      <Link
                        to="/explorer/configs"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        Configurations Explorer
                      </Link>
                    </li>
                    <li className={className("/explorer/experiments")}>
                      <Link
                        to="/explorer/experiments"
                        className=""
                        style={{ cursor: "pointer" }}
                      >
                        Experiments Explorer
                      </Link>
                    </li>
                  </ul>
                </div>
              </div>
            </div>
            <div className="col-lg-10 offset-lg-2 main">
              <div className="row">
                <div className="col-12 izanami-container">
                  <DynamicTitle />
                  <div className="row">
                    <Switch>
                      <Route
                        exact
                        path="/"
                        component={(props) => this.decorate(HomePage, props)}
                      />

                      <Route
                        path="/features/:taction/:titem"
                        component={(props) =>
                          this.decorate(FeaturesPage, props)
                        }
                      />
                      <Route
                        path="/features/:taction"
                        component={(props) =>
                          this.decorate(FeaturesPage, props)
                        }
                      />
                      <Route
                        path="/features"
                        component={(props) =>
                          this.decorate(FeaturesPage, props)
                        }
                      />

                      <Route
                        path="/configurations/:taction/:titem"
                        component={(props) =>
                          this.decorate(ConfigurationsPage, props)
                        }
                      />
                      <Route
                        path="/configurations/:taction"
                        component={(props) =>
                          this.decorate(ConfigurationsPage, props)
                        }
                      />
                      <Route
                        path="/configurations"
                        component={(props) =>
                          this.decorate(ConfigurationsPage, props)
                        }
                      />

                      <Route
                        path="/webhooks/:taction/:titem"
                        component={(props) =>
                          this.decorate(WebHooksPage, props)
                        }
                      />
                      <Route
                        path="/webhooks/:taction"
                        component={(props) =>
                          this.decorate(WebHooksPage, props)
                        }
                      />
                      <Route
                        path="/webhooks"
                        component={(props) =>
                          this.decorate(WebHooksPage, props)
                        }
                      />

                      <Route
                        path="/scripts/:taction/:titem"
                        component={(props) =>
                          this.decorate(GlobalScriptsPage, props)
                        }
                      />
                      <Route
                        path="/scripts/:taction"
                        component={(props) =>
                          this.decorate(GlobalScriptsPage, props)
                        }
                      />
                      <Route
                        path="/scripts"
                        component={(props) =>
                          this.decorate(GlobalScriptsPage, props)
                        }
                      />

                      <Route
                        path="/experiments/:taction/:titem"
                        component={(props) =>
                          this.decorate(ExperimentsPage, props)
                        }
                      />
                      <Route
                        path="/experiments/:taction"
                        component={(props) =>
                          this.decorate(ExperimentsPage, props)
                        }
                      />
                      <Route
                        path="/experiments"
                        component={(props) =>
                          this.decorate(ExperimentsPage, props)
                        }
                      />
                      <Route
                        path="/loggers"
                        component={(props) => this.decorate(LoggersPage, props)}
                      />

                      {userManagementEnabled && (
                        <Route
                          path="/users/:taction/:titem"
                          component={(props) => this.decorate(UserPage, props)}
                        />
                      )}
                      {userManagementEnabled && (
                        <Route
                          path="/users/:taction"
                          component={(props) => this.decorate(UserPage, props)}
                        />
                      )}
                      {userManagementEnabled && (
                        <Route
                          path="/users"
                          component={(props) => this.decorate(UserPage, props)}
                        />
                      )}

                      {apikeyManagementEnabled && (
                        <Route
                          path="/apikeys/:taction/:titem"
                          component={(props) =>
                            this.decorate(ApikeyPage, props)
                          }
                        />
                      )}
                      {apikeyManagementEnabled && (
                        <Route
                          path="/apikeys/:taction"
                          component={(props) =>
                            this.decorate(ApikeyPage, props)
                          }
                        />
                      )}
                      {apikeyManagementEnabled && (
                        <Route
                          path="/apikeys"
                          component={(props) =>
                            this.decorate(ApikeyPage, props)
                          }
                        />
                      )}

                      <Route
                        path="/explorer/configs"
                        component={(props) =>
                          this.decorate(ConfigExplorerPage, props)
                        }
                      />
                      <Route
                        path="/explorer/features"
                        component={(props) =>
                          this.decorate(FeaturesExplorerPage, props)
                        }
                      />
                      <Route
                        path="/explorer/experiments"
                        component={(props) =>
                          this.decorate(ExperimentsExplorerPage, props)
                        }
                      />
                      <Route
                        component={(props) =>
                          this.decorate(NotFoundPage, props)
                        }
                      />
                    </Switch>
                    {changeme && (
                      <SweetModal
                        type="confirm"
                        id={"createUser"}
                        confirm={e => this.gotoCreateUser()}
                        open={true}
                        labelValid="Create a user"
                        title="Create a user"
                        >
                          <div>
                            <p>You're using a temporary user, please create a dedicated one here</p>
                          </div>
                        </SweetModal>
                    )
                    }
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }
}

export class IzanamiApp extends Component {
  render() {
    return (
      <Switch>
        <Route key="route-login" path="/login" component={LoginPage} />,
        <PrivateRoute
          key="private-route"
          path="/"
          component={LoggedApp}
          {...this.props}
        />
      </Switch>
    );
  }
}

const PrivateRoute = ({ component: Component, ...rest }) => {
  return (
    <Route
      {...rest}
      render={(props) => {
        //User is passed from the LoginPage or send by the app in the page.
        const user =
          rest.user && !isEmpty(rest.user)
            ? rest.user
            : props.location.user || {};
        return user.email ? (
          <Component {...rest} {...props} user={user} />
        ) : (
          <Redirect
            to={{
              pathname: `${window.__contextPath}/login`,
              state: { from: props.location },
            }}
          />
        );
      }}
    />
  );
};

const IzanamiAppRouter = withRouter(IzanamiApp);

export function buildRoutedApp(browserHistory) {
  return class RoutedIzanamiApp extends Component {
    render() {
      return (
        <Router history={browserHistory}>
          <IzanamiAppRouter {...this.props} />
        </Router>
      );
    }
  };
}
