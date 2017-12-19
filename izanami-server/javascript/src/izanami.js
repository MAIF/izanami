import 'es6-shim';
import 'whatwg-fetch';
import Symbol from 'es-symbol';
import $ from 'jquery';
import 'react-select-plus/dist/react-select-plus.css';

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
import { RoutedIzanamiApp } from './izanami/index';
import ReactDOM from 'react-dom';

export function init(node, logout, enabledUserManagement, user) {
  ReactDOM.render(<RoutedIzanamiApp user={user} logout={logout} enabledUserManagement={enabledUserManagement}/>, node);
}