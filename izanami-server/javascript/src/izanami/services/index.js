function rawResponse(r) {
  if (r.status === 401) {
    console.log(401, 'Go to login')
    window.location.href = "/login";
    return {};
  } else {
    return r;
  }
}

function jsonBody(r) {
  return rawResponse(r).json();
}

function handleResponse(r) {
  return jsonBody(r)
    .then(json => {
      if (r.ok) {
        return { status: r.status, body: json };
      } else {
        return { status: r.status, body: {}, error: json };
      }
    })
    .catch(e => ({ status: r.status, body: {}, error: e }));
}

export function fetchLogin(user) {
  return fetch(`${window.__contextPath}/api/login`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(user)
  }).then(handleResponse);
}

export function fetchFeatures(args) {
  const { search = "*", pageSize = 10, page = 1 } = args;
  return fetch(
    `${window.__contextPath}/api/features?pageSize=${pageSize}&page=${page}&pattern=${search}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  )
  .then(jsonBody)
  .then(({ results, metadata: { page, pageSize, count, nbPages } }) => ({
    results,
    nbPages,
    page,
    pageSize,
    count
  }));
}

export function fetchFeaturesTree(args) {
  const { search = "*" } = args;
  return fetch(
    `${window.__contextPath}/api/features?pattern=${search}&render=tree`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  ).then(jsonBody);
}

export function fetchFeature(id) {
  return fetch(`${window.__contextPath}/api/features/${id}`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function createFeature(feature) {
  return fetch(`${window.__contextPath}/api/features`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(feature)
  }).then(rawResponse);
}

export function copyFeatureNodes(from, to, active) {
  return fetch(
      `${window.__contextPath}/api/features/_copy`,
      {
        method: "POST",
        credentials: "include",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json"
        },
        body: JSON.stringify({from, to, default:active})
      }
  ).then(rawResponse);
}
export function updateFeature(id, feature) {
  return fetch(`${window.__contextPath}/api/features/${id}`, {
    method: "PUT",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(feature)
  }).then(rawResponse);
}

export function deleteFeature(id, feature) {
  return fetch(`${window.__contextPath}/api/features/${id}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(rawResponse);
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function fetchConfigs(args) {
  const { search = "*", pageSize = 10, page = 1 } = args;
  return fetch(
    `${window.__contextPath}/api/configs?pageSize=${pageSize}&page=${page}&pattern=${search}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  )
    .then(jsonBody)
    .then(({ results, metadata: { page, pageSize, count, nbPages } }) => ({
      results,
      nbPages,
      page,
      pageSize,
      count
    }));
}

export function fetchConfigsTree(args) {
  const { search = "*" } = args;
  return fetch(
    `${window.__contextPath}/api/configs?pattern=${search}&render=tree`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  ).then(jsonBody);
}

export function fetchConfig(id) {
  return fetch(`${window.__contextPath}/api/configs/${id}`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function createConfig(config) {
  return fetch(`${window.__contextPath}/api/configs`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(config)
  }).then(rawResponse);
}

export function updateConfig(id, config) {
  return fetch(`${window.__contextPath}/api/configs/${id}`, {
    method: "PUT",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(config)
  }).then(rawResponse);
}

export function deleteConfig(id) {
  return fetch(`${window.__contextPath}/api/configs/${id}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(rawResponse);
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function fetchUsers(args) {
  const { search = "*", pageSize = 10, page = 1 } = args;
  return fetch(
    `${window.__contextPath}/api/users?pageSize=${pageSize}&page=${page}&pattern=${search}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  )
    .then(jsonBody)
    .then(({ results, metadata: { page, pageSize, count, nbPages } }) => ({
      results,
      nbPages,
      page,
      pageSize,
      count
    }));
}

export function fetchUser(id) {
  return fetch(`${window.__contextPath}/api/users/${id}`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function createUser(user) {
  return fetch(`${window.__contextPath}/api/users`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(user)
  }).then(rawResponse);
}

export function updateUser(id, user) {
  return fetch(`${window.__contextPath}/api/users/${id}`, {
    method: "PUT",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(user)
  }).then(rawResponse);
}

export function deleteUser(id, user) {
  return fetch(`${window.__contextPath}/api/users/${id}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(rawResponse);
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function fetchApikeys(args) {
  const { search = "*", pageSize = 10, page = 1 } = args;
  return fetch(
    `${window.__contextPath}/api/apikeys?pageSize=${pageSize}&page=${page}&pattern=${search}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  )
    .then(jsonBody)
    .then(({ results, metadata: { page, pageSize, count, nbPages } }) => ({
      results,
      nbPages,
      page,
      pageSize,
      count
    }));
}
export function fetchApiKey(id) {
  return fetch(`${window.__contextPath}/api/apikeys/${id}`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function createApikey(apikey) {
  return fetch(`${window.__contextPath}/api/apikeys`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(apikey)
  }).then(rawResponse);
}

export function updateApikey(id, apikey) {
  return fetch(`${window.__contextPath}/api/apikeys/${id}`, {
    method: "PUT",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(apikey)
  }).then(rawResponse);
}

export function deleteApikey(id, apikey) {
  return fetch(`${window.__contextPath}/api/apikeys/${id}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(rawResponse);
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function fetchWebHooks(args) {
  const { search = "*", pageSize = 10, page = 1 } = args;
  return fetch(
    `${window.__contextPath}/api/webhooks?pageSize=${pageSize}&page=${page}&pattern=${search}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  )
    .then(jsonBody)
    .then(({ results, metadata: { page, pageSize, count, nbPages } }) => ({
      results,
      nbPages,
      page,
      pageSize,
      count
    }));
}

export function fetchWebhook(id) {
  return fetch(`${window.__contextPath}/api/webhooks/${id}`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function createWebHook(webhook) {
  return fetch(`${window.__contextPath}/api/webhooks`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(webhook)
  }).then(rawResponse);
}

export function updateWebHook(id, webhook) {
  return fetch(`${window.__contextPath}/api/webhooks/${id}`, {
    method: "PUT",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(webhook)
  }).then(rawResponse);
}

export function deleteWebHook(id, webhook) {
  return fetch(`${window.__contextPath}/api/webhooks/${id}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(rawResponse);
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function fetchConfigsCount() {
  return fetch(`${window.__contextPath}/api/counts/configs`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  })
    .then(jsonBody)
    .then(r => r.count);
}

export function fetchFeaturesCount() {
  return fetch(`${window.__contextPath}/api/counts/features`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  })
    .then(jsonBody)
    .then(r => r.count);
}

export function fetchWebHooksCount() {
  return fetch(`${window.__contextPath}/api/counts/webhooks`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  })
    .then(jsonBody)
    .then(r => r.count);
}

export function fetchSentNotificationCount() {
  return fetch(`${window.__contextPath}/api/counts/notifications`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  })
    .then(jsonBody)
    .then(r => r.count);
}

export function fetchUpdatesCount() {
  return fetch(`${window.__contextPath}/api/counts/updates`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  })
    .then(jsonBody)
    .then(r => r.count);
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function fetchScripts(args) {
  const { search = "*", pageSize = 10, page = 1 } = args;
  return fetch(
    `${window.__contextPath}/api/scripts?pageSize=${pageSize}&page=${page}&pattern=${search}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  )
    .then(jsonBody)
    .then(({ results, metadata: { page, pageSize, count, nbPages } }) => ({
      results,
      nbPages,
      page,
      pageSize,
      count
    }));
}

export function fetchScript(id) {
  return fetch(`${window.__contextPath}/api/scripts/${id}`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function fetchScriptNames() {
  return fetch(`${window.__contextPath}/api/scripts?name_only=true`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function createScript(script) {
  return fetch(`${window.__contextPath}/api/scripts`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(script)
  }).then(rawResponse);
}

export function updateScript(id, script) {
  return fetch(`${window.__contextPath}/api/scripts/${id}`, {
    method: "PUT",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(script)
  }).then(rawResponse);
}

export function deleteScript(id, script) {
  return fetch(`${window.__contextPath}/api/scripts/${id}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(rawResponse);
}

export function debugScript(script, language, context) {
  return fetch(`${window.__contextPath}/api/scripts/_debug`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      context,
      script: {
        type: language,
        script
      }
    })
  }).then(jsonBody);
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function fetchExperiments(args) {
  const { search = "*", pageSize = 10, page = 1 } = args;
  return fetch(
    `${window.__contextPath}/api/experiments?pageSize=${pageSize}&page=${page}&pattern=${search}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  )
    .then(jsonBody)
    .then(({ results, metadata: { page, pageSize, count, nbPages } }) => ({
      results,
      nbPages,
      page,
      pageSize,
      count
    }));
}

export function fetchExperimentsTree(args) {
  const { search = "*" } = args;
  return fetch(
    `${window.__contextPath}/api/experiments?pattern=${search}&render=tree`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  ).then(jsonBody);
}

export function fetchExperiment(id) {
  return fetch(`${window.__contextPath}/api/experiments/${id}`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function fetchExperimentResult(id) {
  return fetch(`${window.__contextPath}/api/experiments/${id}/results`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    },
    timeout: 0
  }).then(jsonBody);
}

export function fetchExperimentVariant(id, clientId) {
  return fetch(
    `${window.__contextPath}/api/experiments/${id}/variant?clientId=${clientId}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  ).then(jsonBody);
}

export function fetchExperimentTree(pattern, clientId) {
  return fetch(
    `${window.__contextPath}/api/tree/experiments?clientId=${clientId}&pattern=${pattern}`,
    {
      method: "GET",
      credentials: "include",
      headers: {
        Accept: "application/json"
      }
    }
  ).then(jsonBody);
}

export function createExperiment(experiment) {
  return fetch(`${window.__contextPath}/api/experiments`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(experiment)
  }).then(rawResponse);
}

export function updateExperiment(id, experiment) {
  return fetch(`${window.__contextPath}/api/experiments/${id}`, {
    method: "PUT",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(experiment)
  }).then(rawResponse);
}

export function deleteExperiment(id, experiment) {
  return fetch(`${window.__contextPath}/api/experiments/${id}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(rawResponse);
}

export function fetchExperimentsCount() {
  return fetch(`${window.__contextPath}/api/counts/experiments`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  })
    .then(jsonBody)
    .then(r => r.count);
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function fetchConfigGraph(pattern) {
  return fetch(`${window.__contextPath}/api/tree/configs?pattern=${pattern}`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function fetchFeatureGraph(pattern, context) {
  return fetch(`${window.__contextPath}/api/tree/features?pattern=${pattern}`, {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(context)
  }).then(jsonBody);
}

export function fetchLoggers() {
  return fetch(`${window.__contextPath}/api/bo/loggers`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json"
    }
  }).then(jsonBody);
}

export function changeLogLevel(name, level) {
  return fetch(
    `${window.__contextPath}/api/bo/loggers/${name}/level?newLevel=${level}`,
    {
      method: "PUT",
      credentials: "include",
      headers: {
        Accept: "application/json"
      },
      body: "{}"
    }
  ).then(jsonBody);
}
