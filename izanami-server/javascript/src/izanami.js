import "es6-shim";
import "whatwg-fetch";
import Symbol from "es-symbol";


import 'bootstrap/dist/css/bootstrap.css';
import "react-table/react-table.css";

import 'bootstrap/dist/js/bootstrap';

if (!window.Symbol) {
  window.Symbol = Symbol;
}



Array.prototype.flatMap = function(lambda) {
  return Array.prototype.concat.apply([], this.map(lambda));
};

import React from "react";
import { buildRoutedApp } from "./izanami/index";
import ReactDOM from "react-dom";
import { createBrowserHistory } from "history";

export function init(node, logout, confirmationDialog, userManagementMode, enabledApikeyManagement, user) {
  let history;
  if (window.__contextPath && window.__contextPath !== "") {
    history = createBrowserHistory({ basename: window.__contextPath });
  } else {
    history = createBrowserHistory();
  }
  const RoutedIzanamiApp = buildRoutedApp(history);
  ReactDOM.render(
    <RoutedIzanamiApp
      user={user}
      logout={logout}
      confirmationDialog={confirmationDialog}
      userManagementMode={userManagementMode}
      enabledApikeyManagement={enabledApikeyManagement}
    />,
    node
  );
}
