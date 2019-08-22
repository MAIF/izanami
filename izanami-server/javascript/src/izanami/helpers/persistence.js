import Cookies from "js-cookie";

export function set(key, value) {
  if (typeof Storage !== "undefined") {
    window.localStorage.setItem(key, value);
  } else {
    Cookies.set(key, value);
  }
}

export function get(key) {
  if (typeof Storage !== "undefined") {
    return window.localStorage.getItem(key);
  } else {
    return Cookies.get(key);
  }
}

export function del(key) {
  if (typeof Storage !== "undefined") {
    return window.localStorage.removeItem(key);
  } else {
    return Cookies.remove(key);
  }
}
