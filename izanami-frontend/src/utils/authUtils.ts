export function isAuthenticated() {
  return document.cookie.includes("token=");
}
