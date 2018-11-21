import 'es6-shim';
import 'whatwg-fetch';
import Symbol from 'es-symbol';
import $ from 'jquery';

//import 'react-select/dist/react-select.css';

import 'react-table/react-table.css';

if (!window.Symbol) {
  window.Symbol = Symbol;
}
window.$ = $;
window.jQuery = $;

require('bootstrap/dist/js/bootstrap.min');

Array.prototype.flatMap = function (lambda) {
  return Array.prototype.concat.apply([], this.map(lambda));
};

import React from 'react';
import { buildRoutedApp } from './izanami/index';
import ReactDOM from 'react-dom';
import { createBrowserHistory } from "history";

export function init(node, logout, enabledUserManagement, user) {
    let history;
    if (window.__contextPath && window.__contextPath !== '') {
        history = createBrowserHistory({basename:window.__contextPath});
    } else {
        history = createBrowserHistory();
    }
  const RoutedIzanamiApp = buildRoutedApp(history);
  ReactDOM.render(<RoutedIzanamiApp user={user} logout={logout} enabledUserManagement={enabledUserManagement}/>, node);
}