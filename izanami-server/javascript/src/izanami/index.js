import React, {Component} from "react";
import {Link, Navigate, Route, BrowserRouter as Router, Routes, useLocation, useParams,useNavigate} from "react-router-dom";
import javascriptWorkerUrl from "file-loader!ace-builds/src-noconflict/worker-javascript";
const ace = require('ace-builds/src-noconflict/ace');
ace.config.setModuleUrl("ace/mode/javascript_worker", javascriptWorkerUrl)
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
import {SweetModal} from "./inputs/SweetModal";
import queryString from "query-string";
import isEmpty from "lodash/isEmpty";
import "../styles/main.scss";
import {MultiSearch} from "./inputs";
import {DynamicTitle} from "./components/DynamicTitle";
import {IzanamiEvents} from "./services/events";
import * as IzanamiServices from "./services";
import Cookies from "js-cookie";

const pictos = {
  configurations: "fas fa-wrench",
  features: "fas fa-toggle-on",
  experiments: "fas fa-flask",
  scripts: "far fa-file-alt",
  webhooks: "fas fa-plug",
};


function Decorate(props) {
  const { component: Component } = props;
  const params = useParams() || {};
  const navigate = useNavigate();
  const newProps = {...props, navigate, params};
  const query =
    queryString.parse((props.location || {search: ""}).search) || {};
  newProps.location.query = query;
  return (
    <Component
      setTitle={(t) => DynamicTitle.setContent(t)}
      getTitle={() => DynamicTitle.getContent()}
      user={props.user}
      userManagementMode={props.userManagementMode}
      confirmationDialog={props.confirmationDialog}
      setSidebarContent={(c) => DynamicSidebar.setContent(c)}
      {...newProps}
    />
  );
};

export class LoggedApp extends Component {
  state = {
    version: "",
  };

  componentDidMount() {
    IzanamiEvents.start();
    IzanamiServices.appInfo().then((appInfo) => {
      this.setState({version: appInfo.version});
    });
    this.props.history.listen(() => {
      document.getElementById("navbarSupportedContent").classList.remove("show");
    });
  }

  componentWillUnmount() {
    IzanamiEvents.stop();
  }

  searchServicesOptions = ({query, filters}) => {
    return IzanamiServices.search({query, filters}).then((results) => {
      return results.map((v) => ({
        label: `${v.id}`,
        type: v.type,
        value: v.id,
      }));
    });
  };

  lineRenderer = (option) => {
    return (
      <label style={{cursor: "pointer"}} className="justify-content-start">
        <i className={`me-2 ${pictos[option.type]}`}/> {option.label}
      </label>
    );
  };

  gotoService = (e) => {
    window.location.href = `${window.__contextPath}/${e.type}/edit/${e.value}`;
  };

  gotoCreateUser = () => {
    window.location.href = `${window.__contextPath}/users/add`;
  };

  onChangemeClosed = () => {
    Cookies.remove("notifyuser");
  };

  render() {
    const pathname = this.props.location.pathname;
    const className = (part) => (pathname.startsWith(part) ? "active" : "inactive");

    const userManagementEnabled =
      this.props.userManagementMode !== "None" &&
      this.props.user &&
      this.props.user.admin;

    const apikeyManagementEnabled =
      this.props.enabledApikeyManagement &&
      this.props.user &&
      this.props.user.admin;

    const selected = (this.props.params || {}).lineId;

    const changeme = Cookies.get("notifyuser") || this.props.user.changeme || this.props.user.temporary;

    return (
      <div className="container-fluid">
        <nav className="navbar navbar-expand-lg fixed-top p-0">
          {/* <div className="container-fluid"> */}
          <div className="navbar-header justify-content-between justify-content-lg-center col-12 col-lg-2 d-flex px-3">
            <a className="navbar-brand" href={window.__contextPath}>
              <div className="d-flex flex-column justify-content-center align-items-center">
                <span>イザナミ</span> Izanami
              </div>
            </a>
            <button
              className="navbar-toggler menu" type="button" data-bs-toggle="collapse"
              data-bs-target="#navbarSupportedContent" aria-controls="navbarSupportedContent" aria-expanded="false"
              aria-label="Toggle navigation">
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
                    style={{fontSize: 20, cursor: "pointer"}}>
                    {selected}
                  </span>
              </div>
            )}
            <div>
              <MultiSearch
                filters={[
                  {name: "features", label: "Features", active: true},
                  {name: "configs", label: "Configurations", active: true},
                  {name: "experiments", label: "Experiments", active: true},
                  {name: "scripts", label: "Scripts", active: true},
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
                data-bs-toggle="dropdown">
                <i className="fas fa-cog" aria-hidden="true"/>
              </a>
              <ul
                className="dropdown-menu dropdown-menu-right"
                aria-labelledby="navbarDarkDropdownMenuLink">
                {userManagementEnabled && (
                  <li key="li-users" className="dropdown-item">
                    <Link
                      to={`${window.__contextPath}/users`}
                      className=""
                      style={{cursor: "pointer"}}>
                      <i className="fas fa-user me-2"/> Users management
                    </Link>
                  </li>
                )}
                {apikeyManagementEnabled && (
                  <li key="li-apikeys" className="dropdown-item">
                    <Link
                      to={`${window.__contextPath}/apikeys`}
                      className=""
                      style={{cursor: "pointer"}}>
                      <i className="fas fa-key me-2"/> Api Keys management
                    </Link>
                  </li>
                )}
                <li className="dropdown-item">
                  <a href={this.props.logout} className="link-logout">
                    <i className="fas fa-power-off me-2"/>{" "}
                    {this.props.user ? this.props.user.email : ""}
                  </a>
                </li>
                <li>
                  <div className="dropdown-divider"/>
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
                        <Link to={`${window.__contextPath}`}>
                          <i className="fas fa-tachometer-alt"/> Home
                        </Link>
                      </h3>
                    </li>
                    <li className={className("/features")}>
                      <Link
                        to={`${window.__contextPath}/features`}
                        className=""
                        style={{cursor: "pointer"}}>
                        <i className="fas fa-toggle-on"/>
                        Features
                      </Link>
                    </li>
                    <li className={className("/configurations")}>
                      <Link
                        to={`${window.__contextPath}/configurations`}
                        className=""
                        style={{cursor: "pointer"}}>
                        <i className="fas fa-wrench"/>
                        Configurations
                      </Link>
                    </li>
                    <li className={className("/experiments")}>
                      <Link
                        to={`${window.__contextPath}/experiments`}
                        className=""
                        style={{cursor: "pointer"}}>
                        <i className="fas fa-flask"/>
                        Experiments
                      </Link>
                    </li>
                    <li className={className("/scripts")}>
                      <Link
                        to={`${window.__contextPath}/scripts`}
                        className=""
                        style={{cursor: "pointer"}}>
                        <i className="far fa-file-alt"/>
                        Global Scripts
                      </Link>
                    </li>
                    <li className={className("/webhooks")}>
                      <Link
                        to={`${window.__contextPath}/webhooks`}
                        className=""
                        style={{cursor: "pointer"}}>
                        <i className="fas fa-plug"/>
                        WebHooks
                      </Link>
                    </li>
                  </ul>
                  <ul className="nav nav-sidebar  flex-column">
                    <li className={className("/")}>
                      <h3 style={{marginTop: 0}}>
                        <i className="fas fa-tachometer-alt"/> Explore
                      </h3>
                    </li>
                    <li className={className("/explorer/features")}>
                      <Link
                        to={`${window.__contextPath}/explorer/features`}
                        className=""
                        style={{cursor: "pointer"}}>
                        Features Explorer
                      </Link>
                    </li>
                    <li className={className("/explorer/configs")}>
                      <Link
                        to={`${window.__contextPath}/explorer/configs`}
                        className=""
                        style={{cursor: "pointer"}}>
                        Configurations Explorer
                      </Link>
                    </li>
                    <li className={className("/explorer/experiments")}>
                      <Link
                        to={`${window.__contextPath}/explorer/experiments`}
                        className=""
                        style={{cursor: "pointer"}}>
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
                  <DynamicTitle/>
                  <div className="row">
                    <Routes>
                      <Route
                        exact
                        path={`${window.__contextPath}/`}
                        element={<Decorate component={HomePage} {...this.props}/>}/>

                      <Route
                        path={`${window.__contextPath}/features/:taction/:titem`}
                        element={<Decorate component={FeaturesPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/features/:taction`}
                        element={<Decorate component={FeaturesPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/features`}
                        element={<Decorate component={FeaturesPage} {...this.props}/>}/>

                      <Route
                        path={`${window.__contextPath}/configurations/:taction/:titem`}
                        element={<Decorate component={ConfigurationsPage} {...this.props}/>}
                        component={(props) =>
                          this.decorate(ConfigurationsPage, props)
                        }/>
                      <Route
                        path={`${window.__contextPath}/configurations/:taction`}
                        element={<Decorate component={ConfigurationsPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/configurations`}
                        element={<Decorate component={ConfigurationsPage} {...this.props}/>}/>

                      <Route
                        path={`${window.__contextPath}/webhooks/:taction/:titem`}
                        element={<Decorate component={WebHooksPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/webhooks/:taction`}
                        element={<Decorate component={WebHooksPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/webhooks`}
                        element={<Decorate component={WebHooksPage} {...this.props}/>}/>

                      <Route
                        path={`${window.__contextPath}/scripts/:taction/:titem`}
                        element={<Decorate component={GlobalScriptsPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/scripts/:taction`}
                        element={<Decorate component={GlobalScriptsPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/scripts`}
                        element={<Decorate component={GlobalScriptsPage} {...this.props}/>}/>

                      <Route
                        path={`${window.__contextPath}/experiments/:taction/:titem`}
                        element={<Decorate component={ExperimentsPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/experiments/:taction`}
                        element={<Decorate component={ExperimentsPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/experiments`}
                        element={<Decorate component={ExperimentsPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/loggers`}
                        element={<Decorate component={LoggersPage} {...this.props}/>}/>

                      {userManagementEnabled && (
                        <Route
                          path={`${window.__contextPath}/users/:taction/:titem`}
                          element={<Decorate component={UserPage} {...this.props}/>}/>
                      )}
                      {userManagementEnabled && (
                        <Route
                          path={`${window.__contextPath}/users/:taction`}
                          element={<Decorate component={UserPage} {...this.props}/>}/>
                      )}
                      {userManagementEnabled && (
                        <Route
                          path={`${window.__contextPath}/users`}
                          element={<Decorate component={UserPage} {...this.props}/>}/>
                      )}
                      {userManagementEnabled && (
                        <Route
                          path={`${window.__contextPath}/:taction/:titem`}
                          element={<Decorate component={UserPage} {...this.props}/>}/>
                      )}

                      {apikeyManagementEnabled && (
                        <Route
                          path={`${window.__contextPath}/apikeys/:taction/:titem`}
                          element={<Decorate component={ApikeyPage} {...this.props}/>}/>
                      )}
                      {apikeyManagementEnabled && (
                        <Route
                          path={`${window.__contextPath}/apikeys/:taction`}
                          element={<Decorate component={ApikeyPage} {...this.props}/>}/>
                      )}
                      {apikeyManagementEnabled && (
                        <Route
                          path={`${window.__contextPath}/apikeys`}
                          element={<Decorate component={ApikeyPage} {...this.props}/>}/>
                      )}

                      <Route
                        path={`${window.__contextPath}/explorer/configs`}
                        element={<Decorate component={ConfigExplorerPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/explorer/features`}
                        element={<Decorate component={FeaturesExplorerPage} {...this.props}/>}/>
                      <Route
                        path={`${window.__contextPath}/explorer/experiments`}
                        element={<Decorate component={ExperimentsExplorerPage} {...this.props}/>}/>
                      <Route element={<Decorate component={NotFoundPage} {...this.props}/>}/>
                    </Routes>
                    {changeme && (
                      <SweetModal
                        type="confirm"
                        id={"createUser"}
                        confirm={() => this.gotoCreateUser()}
                        onDismiss={() => this.onChangemeClosed()}
                        open={true}
                        labelValid="Create a user"
                        title="Create a user">
                        <div>
                          <p>You're using a temporary user, please create a dedicated one here and delete the old one</p>
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

const PrivateRoute = ({component: Component, ...rest}) => {
  const location = useLocation();
  //User is passed from the LoginPage or send by the app in the page.
  const user =
    rest.user && !isEmpty(rest.user)
      ? rest.user
      : rest.location.user || {};
  return user.email ? (
    <Component {...rest} user={user} location={location}/>
  ) : (
    <Navigate to={`${window.__contextPath}/login`} replace/>
  )
};

export function RoutedIzanamiApp(props) {
  return (
    <Router>
      <Routes>
        <Route key="route-login" path={`${window.__contextPath}/login`} element={<LoginPage />} />,
        <Route key="private-route" path="*" element={<PrivateRoute component={LoggedApp} {...props}/>}/>
      </Routes>
     </Router>
  );
}
