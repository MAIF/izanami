
let izanamiListeners = {};

export function izanamiReload(path, fetchHeaders) {
  fetch(path, {
    method: 'GET',
    credentials: 'include',
    headers: fetchHeaders,
  }).then(r => r.json()).then(data => {
    const listeners = izanamiListeners[path] || [];
    listeners.forEach(l => {
      try {
        l(data)
      } catch (err) {
        console.error(err);
      }
    });
  });
}


export function register(path, callback) {
  const listeners = izanamiListeners[path] || [];
  const index = listeners.indexOf(callback);
  if (index > -1) {
    listeners.splice(index, 1);
  }
  listeners.push(callback);
  izanamiListeners = {...izanamiListeners, [path]: listeners}
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
