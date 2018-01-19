
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
        return r.json()
      } else {
        return {};
      }
    })
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

export function todoLists() {
  return fetch("/api/todolists", {
    method: 'GET',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  })
  .then(r => r.json())
}

export function createTodoList(todoList) {
  return fetch("/api/todolists", {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(todoList)
  })
  .then(r => r.json())
}

export function getTodoList(name) {
  return fetch(`/api/todolists/${name}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  })
  .then(r => {
    if (r.status === 404) {
      window.location.href = '/';
    }
    return r.json()
  })
}

export function updateTodoList(name, todoList) {
  return fetch(`/api/todolists/${name}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(todoList)
  })
    .then(r => r.json())
}

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

export function removeTodoList(name) {
  return fetch(`/api/todolists/${name}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  });
}


export function listItems(name) {
  return fetch(`/api/todolists/${name}/items`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    },
  })
    .then(r => r.json())
}

export function addItem(name, item) {
  return fetch(`/api/todolists/${name}/items`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(item)
  })
    .then(r => r.json())
}

export function removeAllItems(name, done) {
  return fetch(`/api/todolists/${name}/items?done=${done}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  });
}

export function updateItem(name, itemName, item) {
  return fetch(`/api/todolists/${name}/items/${itemName}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(item)
  })
    .then(r => r.json())
}


export function removeItem(name, itemName) {
  return fetch(`/api/todolists/${name}/items/${itemName}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  })
}