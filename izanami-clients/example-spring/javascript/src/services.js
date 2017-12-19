
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
  document.cookie = 'clientId=; expires=Thu, 01 Jan 1970 00:00:01 GMT;';
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