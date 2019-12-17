
let izanamiFetchs = {};
let izanamiListeners = {};

export function izanamiReload(id) {
  let izanamiFetch = izanamiFetchs[id];
  if (izanamiFetch) {
      console.debug("Fetching for id ", id);
      return izanamiFetch().then(r => r.json()).then(data => {
          const listeners = izanamiListeners[id] || [];
          listeners.forEach(l => {
              try {
                  l(data)
              } catch (err) {
                  console.error(err);
              }
          });
      });
  }
}

export function registerFetch(id, fetchMethod) {
    izanamiFetchs = {...izanamiFetchs, [id]: fetchMethod}
}

export function register(id, callback) {
  const listeners = izanamiListeners[id] || [];
  const index = listeners.indexOf(callback);
  if (index > -1) {
    listeners.splice(index, 1);
  }
  listeners.push(callback);
  izanamiListeners = {...izanamiListeners, [id]: listeners}
}

export function unregister(path, callback) {
  if (callback && path) {

    const listeners = izanamiListeners[path] || [];
    const index = listeners.indexOf(callback);
    if (index > -1) {
      listeners.splice(index, 1);
      izanamiListeners = {...izanamiListeners, [path]: listeners}
    }
  } else if (path) {
    delete izanamiListeners[path];
  }
}
