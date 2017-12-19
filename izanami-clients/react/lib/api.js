'use strict';

Object.defineProperty(exports, "__esModule", {
  value: true
});

var _extends = Object.assign || function (target) { for (var i = 1; i < arguments.length; i++) { var source = arguments[i]; for (var key in source) { if (Object.prototype.hasOwnProperty.call(source, key)) { target[key] = source[key]; } } } return target; };

exports.izanamiReload = izanamiReload;
exports.register = register;
exports.unregister = unregister;

function _defineProperty(obj, key, value) { if (key in obj) { Object.defineProperty(obj, key, { value: value, enumerable: true, configurable: true, writable: true }); } else { obj[key] = value; } return obj; }

var izanamiListeners = {};

function izanamiReload(path, fetchHeaders) {
  fetch(path, {
    method: 'GET',
    credentials: 'include',
    headers: fetchHeaders
  }).then(function (r) {
    return r.json();
  }).then(function (data) {
    var listeners = izanamiListeners[path] || [];
    listeners.forEach(function (l) {
      try {
        l(data);
      } catch (err) {
        console.error(err);
      }
    });
  });
}

function register(path, callback) {
  var listeners = izanamiListeners[path] || [];
  var index = listeners.indexOf(callback);
  if (index > -1) {
    listeners.splice(index, 1);
  }
  listeners.push(callback);
  izanamiListeners = _extends({}, izanamiListeners, _defineProperty({}, path, listeners));
}

function unregister(path, callback) {
  if (callback && path) {

    var listeners = izanamiListeners[path] || [];
    var index = listeners.indexOf(callback);
    if (index > -1) {
      listeners.splice(index, 1);
      izanamiListeners = _extends({}, izanamiListeners, _defineProperty({}, path, listeners));
    }
  } else if (path) {
    delete izanamiListeners[path];
  }
}