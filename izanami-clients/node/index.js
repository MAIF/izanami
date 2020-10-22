const _ = require('lodash');
const deepmerge = require('deepmerge');
const fetch = require('isomorphic-fetch');

class IzanamicFeatureClient {

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
      const value = _.get(this.config.fallbackConfig, dottyKey) || { active: false };
      return new Promise((s, f) => s(value.active || false));
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
          return (_.get(this.config.fallbackConfig, dottyKey) || { active: false }).active;
        } else {
          return new Promise((s, f) => f({ message: 'Bad response', response: r }));
        }
      } catch(e) {
        return new Promise((s, f) => f(e));
      }
    });
  };

  features(pattern = "*", context = {}) {
    const columnPattern = pattern.replace(/\./g, ":");
    const dottyKey = pattern.replace(/:/g, ".");
    const url = this.config.host + "/api/tree/features?pattern=" + columnPattern;
    if (this.config.env === 'DEV') {
      return new Promise((s, f) => s(_.get(this.config.fallbackConfig, dottyKey)));
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
      } catch(e) {
        return new Promise((s, f) => f(e));
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
      const value = _.get(this.conf.fallbackConfig, dottyKey) || {};
      return new Promise((s, f) => s(value.active || {}));
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
            const mergedValue = deepmerge(_.get(this.conf.fallbackConfig, dottyKey), value);
            return mergedValue || {}
          });
        } else if (r.status === 404) {
          return _.get(this.conf.fallbackConfig, dottyKey) || {};
        } else {
          return new Promise((s, f) => f({ message: 'Bad response', response: r }));
        }
      } catch(e) {
        return new Promise((s, f) => f(e));
      }
    });
  };

  configs(pattern = "*") {
    const columnPattern = pattern.replace(/\./g, ":");
    const dottyKey = pattern.replace(/:/g, ".");
    const url = this.conf.host + "/api/tree/configs?pattern=" + columnPattern;
    if (this.conf.env === 'DEV') {
      return new Promise((s, f) => s(_.get(this.conf.fallbackConfig, dottyKey)));
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
      } catch(e) {
        return new Promise((s, f) => f(e));
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
      const value = _.get(this.conf.fallbackConfig, dottyKey) || {};
      return new Promise((s, f) => s(value.active || {}));
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
            const mergedValue = deepmerge(_.get(this.conf.fallbackConfig, dottyKey), value);
            return mergedValue || {}
          });
        } else if (r.status === 404) {
          return _.get(this.conf.fallbackConfig, dottyKey) || {};
        } else {
          return new Promise((s, f) => f({ message: 'Bad response', response: r }));
        }
      } catch(e) {
        return new Promise((s, f) => f(e));
      }
    });
  };

  experiments(pattern = "*", clientId) {
    const columnPattern = pattern.replace(/\./g, ":");
    const dottyKey = pattern.replace(/:/g, ".");
    const url = this.conf.host + "/api/tree/experiments?pattern=" + columnPattern + "&clientId=" + clientId;
    if (this.conf.env === 'DEV') {
      return new Promise((s, f) => s(_.get(this.conf.fallbackConfig, dottyKey)));
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
      } catch(e) {
        return new Promise((s, f) => f(e));
      }
    });
  };

  variantFor(key, clientId) {
    const columnKey = key.replace(/\./g, ":");
    const dottyKey = key.replace(/:/g, ".");
    const url = this.conf.host + "/api/experiments/" + columnKey + "/variant?clientId=" + clientId
    if (this.conf.env === 'DEV') {
      const value = _.get(this.conf.fallbackConfig, dottyKey) || {};
      return new Promise((s, f) => s(value.active || {}));
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
          const value = _.get(this.conf.fallbackConfig, dottyKey) || {};
          return new Promise((s, f) => s(value.active || {}));
        } else {
          return new Promise((s, f) => f({ message: 'Bad response', response: r }));
        }
      } catch(e) {
        return new Promise((s, f) => f(e));
      }
    });
  }

  displayed(key, clientId) {
    const columnKey = key.replace(/\./g, ":");
    const url = this.conf.host + "/api/experiments/" + columnKey + "/displayed?clientId=" + clientId;
    if (this.conf.env === 'DEV') {
      return new Promise((s, f) => s({}));
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
          return new Promise((s, f) => f({ message: 'Bad response', response: r }));
        }
      } catch(e) {
        return new Promise((s, f) => f(e));
      }
    });
  }

  won(key, clientId) {
    const columnKey = key.replace(/\./g, ":");
    const url = this.conf.host + "/api/experiments/" + columnKey + "/won?clientId=" + clientId;
    if (this.conf.env === 'DEV') {
      return new Promise((s, f) => s({}));
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
          return new Promise((s, f) => f({ message: 'Bad response', response: r }));
        }
      } catch(e) {
        return new Promise((s, f) => f(e));
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
  featureClient: (config) => new IzanamicFeatureClient(config),
  configClient: (config) => new IzanamiConfigClient(config),
  experimentsClient: (config) => new IzanamiExperimentsClient(config),
  expressProxy(config) {

    const { featureClient, configClient, experimentsClient, app, sessionPath, experimentWonPath, experimentDisplayedPath, path } = config;

    app.get(sessionPath || '/api/me', (req, res) => {
      new Promise((s, f) => s({})).then(() => {
        (!!featureClient ? featureClient.features(path, { user: req.user_email }) : new Promise((s, f) => s({}))).then(features => {
          (!!experimentsClient ? experimentsClient.experiments(path, req.user_email) : new Promise((s, f) => s({}))).then(experiments => {
            (!!configClient ? configClient.configs(path) : new Promise((s, f) => s({}))).then(configurations => {
              res.send({ experiments, features, configurations });
            });
          });
        });
      });
    });

    app.post(experimentWonPath || '/api/experiments/won', (req, res) => {
      experimentsClient.won(req.query.experiment, req.user_email).then(() => {
        res.send({ done: true });
      });
    });

    app.post(experimentDisplayedPath || '/api/experiments/displayed', (req, res) => {
      experimentsClient.displayed(req.query.experiment, req.user_email).then(() => {
        res.send({ done: true });
      });
    });
  }
};
