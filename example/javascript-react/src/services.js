
export function notifyWon(key) {
  return fetch(`/api/izanami/experiments/won?experiment=${key}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  });
}

export function me() {
  return fetch("/api/me", {
    method: 'GET',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  })
    .then(r => {
      if (r.status === 200) {
        return r.json();
      } else {
        return ;
      }
    })
}


export function searchTvShow(input) {
  return fetch(`/api/shows/_search?name=${input}&ts=${new Date().getTime()}`)
    .then((response) => {
      return response.json();
    });
}

const callbacks = [];

export function addTvShow(id) {
  return fetch(`/api/me/${id}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  })
    .then(r => {
      if (r.status === 200) {
        return r.json()
      } else {
        return ;
      }
    })
    .then(u => {
      notifiyUserChanged(u);
      return u;
    });
}

export function removeTvShow(id) {
  return fetch(`/api/me/${id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  })
    .then(r => {
      if (r.status === 200) {
        return r.json()
      } else {
        return ;
      }
    })
    .then(u => {
      notifiyUserChanged(u);
      return u;
    });
}

export function markEpisodeWatched(tvdbid, id, bool) {
  return fetch(`/api/me/${tvdbid}/episodes/${id}?watched=${bool}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  })
    .then(r => {
      if (r.status === 200) {
        return r.json()
      } else {
        return ;
      }
    })
    .then(u => {
      notifiyUserChanged(u);
      return u;
    });
}


export function markSeasonWatched(tvdbid, number, bool) {
  return fetch(`/api/me/${tvdbid}/seasons/${number}?watched=${bool}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  })
    .then(r => {
      if (r.status === 200) {
        return r.json()
      } else {
        return ;
      }
    })
    .then(u => {
      notifiyUserChanged(u);
      return u;
    });
}

function notifiyUserChanged(user) {
  if (user) {
    callbacks.forEach(cb => cb(user))
  }
}

export function onUserChange(cb) {
  if (cb) {
    callbacks.push(cb);
  }
}

export function unregister(cb) {
  if (cb) {
    const index = callbacks.indexOf(cb);
    if (index > -1) {
      callbacks.splice(index, 1);
    }
  }
}


export function login(form) {
  return fetch("/api/login", {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(form)
  })
}

export function logout() {
  document.cookie = 'userId=; expires=Thu, 01 Jan 1970 00:00:01 GMT;';
}
