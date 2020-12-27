const get = require('lodash/get');
const deepmerge = require('deepmerge');
const fetch = require('isomorphic-fetch');

class IzanamiFeatureClient {

  constructor(config) {
    this.config = config;
    this.checkFeature = this.checkFeature.bind(this);
    this.features = this.features.bind(this);
  }

  checkFeature(key, context = {}) {
    const columnKey = key.replace(/\./g, ":");
    const dottyKey = key.replace(/:/g, ".");
    const url = this.config.host + "/api/features/" + columnKey + "/check";
    if (this.config.env === 'DEV') {
      const value = get(this.config.fallbackConfig, dottyKey) || {active: false};
      return Promise.resolve(value.active || false);
    }
    const config = this.config;
    return fetch(url, {
      method: 'POST',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [config.clientIdName]: config.clientId,
        [config.clientSecretName]: config.clientSecret,
      },
      body: JSON.stringify(context)
    }).then(r => {
      try {
        if (r.status === 200) {
          return r.json().then(t => !!t.active);
        } else if (r.status === 404) {
          return (get(this.config.fallbackConfig, dottyKey) || {active: false}).active;
        } else {
          return Promise.reject({message: 'Bad response', response: r});
        }
      } catch (e) {
        return Promise.reject(e);
      }
    });
  };

  features(pattern = "*", context = {}) {
    const columnPattern = pattern.replace(/\./g, ":");
    const dottyKey = pattern.replace(/:/g, ".");
    const url = this.config.host + "/api/tree/features?pattern=" + columnPattern;
    if (this.config.env === 'DEV') {
      return Promise.resolve(get(this.config.fallbackConfig, dottyKey));
    }
    const config = this.config;
    return fetch(url, {
      method: 'POST',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [config.clientIdName]: config.clientId,
        [config.clientSecretName]: config.clientSecret,
      },
      body: JSON.stringify(context)
    }).then(r => {
      try {
        return r.json().then(f => {
          return deepmerge(this.config.fallbackConfig, f)
        });
      } catch (e) {
        return Promise.reject(e);
      }
    });
  };
}

class IzanamiConfigClient {

  constructor(conf) {
    this.conf = conf;
    this.config = this.config.bind(this);
    this.configs = this.configs.bind(this);
  }

  config(key) {
    const columnKey = key.replace(/\./g, ":");
    const dottyKey = key.replace(/:/g, ".");
    const url = this.conf.host + "/api/configs/" + columnKey;
    if (this.conf.env === 'DEV') {
      const value = get(this.conf.fallbackConfig, dottyKey) || {};
      return Promise.resolve(value.active || {});
    }
    const conf = this.conf;
    return fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [conf.clientIdName]: conf.clientId,
        [conf.clientSecretName]: conf.clientSecret,
      }
    }).then(r => {
      try {
        if (r.status === 200) {
          return r.json().then(t => {
            const value = t.value;
            const mergedValue = deepmerge(get(this.conf.fallbackConfig, dottyKey), value);
            return mergedValue || {}
          });
        } else if (r.status === 404) {
          return get(this.conf.fallbackConfig, dottyKey) || {};
        } else {
          return Promise.reject({message: 'Bad response', response: r});
        }
      } catch (e) {
        return Promise.reject(e);
      }
    });
  };

  configs(pattern = "*") {
    const columnPattern = pattern.replace(/\./g, ":");
    const dottyKey = pattern.replace(/:/g, ".");
    const url = this.conf.host + "/api/tree/configs?pattern=" + columnPattern;
    if (this.conf.env === 'DEV') {
      return Promise(get(this.conf.fallbackConfig, dottyKey));
    }
    return fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [this.conf.clientIdName]: this.conf.clientId,
        [this.conf.clientSecretName]: this.conf.clientSecret,
      }
    }).then(r => {
      try {
        return r.json().then(f => {
          return deepmerge(this.conf.fallbackConfig, f)
        });
      } catch (e) {
        return Promise.reject(e);
      }
    });
  };
}

class IzanamiExperimentsClient {

  constructor(conf) {
    this.conf = conf;
    this.experiment = this.experiment.bind(this);
    this.experiments = this.experiments.bind(this);
  }

  experiment(key) {
    const columnKey = key.replace(/\./g, ":");
    const dottyKey = key.replace(/:/g, ".");
    const url = this.conf.host + "/api/experiments/" + columnKey;
    if (this.conf.env === 'DEV') {
      const value = get(this.conf.fallbackConfig, dottyKey) || {};
      return Promise.resolve(value.active || {});
    }
    return fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [this.conf.clientIdName]: this.conf.clientId,
        [this.conf.clientSecretName]: this.conf.clientSecret,
      }
    }).then(r => {
      try {
        if (r.status === 200) {
          return r.json().then(t => {
            const value = JSON.parse(t.value);
            const mergedValue = deepmerge(get(this.conf.fallbackConfig, dottyKey), value);
            return mergedValue || {}
          });
        } else if (r.status === 404) {
          return get(this.conf.fallbackConfig, dottyKey) || {};
        } else {
          return Promise.reject({message: 'Bad response', response: r});
        }
      } catch (e) {
        return Promise.reject(e);
      }
    });
  };

  experiments(pattern = "*", clientId) {
    const columnPattern = pattern.replace(/\./g, ":");
    const dottyKey = pattern.replace(/:/g, ".");
    const url = this.conf.host + "/api/tree/experiments?pattern=" + columnPattern + "&clientId=" + clientId;
    if (this.conf.env === 'DEV') {
      return Promise.resolve(get(this.conf.fallbackConfig, dottyKey));
    }
    return fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [this.conf.clientIdName]: this.conf.clientId,
        [this.conf.clientSecretName]: this.conf.clientSecret,
      }
    }).then(r => {
      try {
        return r.json().then(f => {
          return deepmerge(this.conf.fallbackConfig, f)
        });
      } catch (e) {
        return Promise.reject(e);
      }
    });
  };

  variantFor(key, clientId) {
    const columnKey = key.replace(/\./g, ":");
    const dottyKey = key.replace(/:/g, ".");
    const url = this.conf.host + "/api/experiments/" + columnKey + "/variant?clientId=" + clientId
    if (this.conf.env === 'DEV') {
      const value = get(this.conf.fallbackConfig, dottyKey) || {};
      return Promise.resolve(value.active || {});
    }
    return fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [this.conf.clientIdName]: this.conf.clientId,
        [this.conf.clientSecretName]: this.conf.clientSecret,
      }
    }).then(r => {
      try {
        if (r.status === 200) {
          return r.json();
        } else if (r.status === 404) {
          const value = get(this.conf.fallbackConfig, dottyKey) || {};
          return Promise.resolve(value.active || {});
        } else {
          return Promise.reject({message: 'Bad response', response: r});
        }
      } catch (e) {
        return Promise.reject(e);
      }
    });
  }

  displayed(key, clientId) {
    const columnKey = key.replace(/\./g, ":");
    const url = this.conf.host + "/api/experiments/" + columnKey + "/displayed?clientId=" + clientId;
    if (this.conf.env === 'DEV') {
      return Promise.resolve({});
    }
    return fetch(url, {
      method: 'POST',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [this.conf.clientIdName]: this.conf.clientId,
        [this.conf.clientSecretName]: this.conf.clientSecret,
      },
      body: '{}'
    }).then(r => {
      try {
        if (r.status === 200 || r.state === 404) {
          return r;
        } else {
          return Promise.reject({message: 'Bad response', response: r});
        }
      } catch (e) {
        return Promise.reject(e);
      }
    });
  }

  won(key, clientId) {
    const columnKey = key.replace(/\./g, ":");
    const url = this.conf.host + "/api/experiments/" + columnKey + "/won?clientId=" + clientId;
    if (this.conf.env === 'DEV') {
      return Promise.resolve({});
    }
    return fetch(url, {
      method: 'POST',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        [this.conf.clientIdName]: this.conf.clientId,
        [this.conf.clientSecretName]: this.conf.clientSecret,
      },
      body: '{}'
    }).then(r => {
      try {
        if (r.status === 200 || r.state === 404) {
          return r;
        } else {
          return Promise.reject({message: 'Bad response', response: r});
        }
      } catch (e) {
        return Promise.reject(e);
      }
    });
  }
}

module.exports = {
  Env: {
    PROD: 'PROD',
    DEV: 'DEV',
  },
  defaultConfig: {
    timeout: 10000,
    clientIdName: 'Izanami-Client-Id',
    clientSecretName: 'Izanami-Client-Secret',
    fallbackConfig: {},
    clientId: '--',
    clientSecret: '--',
    env: 'PROD'
  },
  featureClient: (config) => new IzanamiFeatureClient(config),
  configClient: (config) => new IzanamiConfigClient(config),
  experimentsClient: (config) => new IzanamiExperimentsClient(config),
  expressProxy(config) {

    const {
      featureClient,
      configClient,
      experimentsClient,
      app,
      sessionPath = '/api/me',
      experimentWonPath = '/api/experiments/won',
      experimentDisplayedPath = '/api/experiments/displayed',
      path
    } = config;


    app.get(sessionPath, (req, res) => {
      Promise.all([
        (!!featureClient ? featureClient.features(path, {user: req.user_email}) : {}),
        (!!experimentsClient ? experimentsClient.experiments(path, req.user_email) : {}),
        (!!configClient ? configClient.configs(path) : {})
      ]).then(([features, experiments, configurations]) => {
        res.send({experiments, features, configurations});
      });
    });

    app.post(experimentWonPath, (req, res) => {
      experimentsClient.won(req.query.experiment, req.user_email).then(() => {
        res.send({done: true});
      });
    });

    app.post(experimentDisplayedPath, (req, res) => {
      experimentsClient.displayed(req.query.experiment, req.user_email).then(() => {
        res.send({done: true});
      });
    });
  }
};
