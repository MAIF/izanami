const Izanami = require("../index")
const express = require("express")
const fetch = require("isomorphic-fetch");
const should = require("chai").should();

const app = express();

const jsonHeaders = {
  "Accept": "application/json",
  "Content-Type": "application/json"
}

async function getAuthentificationsCookie(host) {
  const loginResponse = await fetch(`${host}/api/login`, {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({userId: "admin", password: "admin123"})
  });
  const session = loginResponse.headers.get('set-cookie');
  return session;
}

async function createApiKeyIfNotFound(host, clientId, clientSecret) {
  const session = await getAuthentificationsCookie(host);
  let headers = {
    Cookie: session,
    ...jsonHeaders
  };
  const apikeysResponse = await fetch(`${host}/api/apikeys`, {
    method: "GET", headers
  });
  const apikeys = await apikeysResponse.json()

  if (!apikeys.results.find(a => a.name === clientId)) {
    const apiKeyCreated = await fetch(`${host}/api/apikeys`, {
      method: "POST",
      body: JSON.stringify({
        name: clientId,
        clientId: clientId,
        clientSecret: clientSecret,
        authorizedPatterns: [{pattern: "*", rights: ["C", "R", "U", "D"]}]
      }),
      headers
    })
  }
}

describe("Izanami Node.js", function () {
  let server
  let config
  let headers
  let configClient
  let featureClient
  let experimentsClient

  const experimentId = "experimentA";
  const featureId = "featureA";
  const configurationId = "configurationA";

  before(async function () {
    const host = "http://localhost:9000";
    const clientId = "izanami-node-clientId"
    const clientSecret = "izanami-node-clientSecret"
    await createApiKeyIfNotFound(host, clientId, clientSecret);
    config = Object.assign({}, Izanami.defaultConfig, {
      host: host,
      clientId: clientId,
      clientSecret: clientSecret,
      fallbackConfig: {[experimentId]: {}, [configurationId]: {}}
    });
    headers = {
      ...jsonHeaders,
      [config.clientIdName]: config.clientId,
      [config.clientSecretName]: config.clientSecret,
    };

    // Get a configs client
    configClient = Izanami.configClient(config);
    // Get a feature client
    featureClient = Izanami.featureClient(config);
    // Get a experiments client
    experimentsClient = Izanami.experimentsClient(config);

    Izanami.expressProxy({
      sessionPath: "/api/izanami",
      featureClient, // Optional
      experimentsClient, // Optional
      configClient, // Optional
      app, // Express app,
      experimentsClientIdFromRequest : (req) => "userId",
      experimentWonPath : '/api/izanami/experiments/won',
      experimentDisplayedPath : '/api/izanami/experiments/displayed',
    });
    server = app.listen(3000)
    return server
  });

  after(function () {
    server.close()
  });

  describe("Test feature", function () {
    it('id=a should be not activated, id=aa should be activated', async function () {
      try {
        await fetch(`${config.host}/api/features/${featureId}`, {method: "DELETE", headers})
      } catch (e) {
        // ignore if feature doesn't exist
      }
      await fetch(`${config.host}/api/features`, {
        method: "POST", headers, body: JSON.stringify(
          {enabled: true, activationStrategy: "PERCENTAGE", parameters: {percentage: 50}, id: featureId}
        )
      })
      const checkFalse = await featureClient.checkFeature(featureId, {id: "a"})
      checkFalse.should.be.equal(false)
      const checkTrue = await featureClient.checkFeature(featureId, {id: "aa"})
      checkTrue.should.be.equal(true)
    });
  });

  describe("Test configuration", async function () {
    it("The configuration created must be equal to the configuration returned by the client", async function () {
      try {
        await fetch(`${config.host}/api/configs/${configurationId}`, {method: "DELETE", headers})
      } catch (e) {
        // ignore if feature doesn't exist
      }
      const configurationValue = {id: configurationId, "value": {key: "value"}};
      await fetch(`${config.host}/api/configs`, {
        method: "POST", headers, body: JSON.stringify(
          configurationValue
        )
      })

      const configuration = await configClient.config(configurationId)
      configuration.should.be.deep.equal(configurationValue)
    })
  })

  describe("Test experiments", async function () {
    it("The experiment created must be equal to the experiment returned by the client", async function () {
      try {
        await fetch(`${config.host}/api/experiments/${experimentId}`, {method: "DELETE", headers})
      } catch (e) {
        // ignore if experimentId doesn't exist
      }
      const variantId = "variantA";
      const experimentCreated = await fetch(`${config.host}/api/experiments`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          id: experimentId,
          name: experimentId,
          enabled: true,
          variants: [
            {id: variantId, name: variantId, traffic: 0.50},
            {id: "variantB", name: "variantB", traffic: 0.50}
          ]
        })
      })
      const experimentCreatedJson = await experimentCreated.json()
      const experiment = await experimentsClient.experiment(experimentId);
      experiment.should.be.deep.equal(experimentCreatedJson);
    })
    it("The result should be only one vaariant with only one displayed and only one won", async function () {
      const variant = await experimentsClient.variantFor(experimentId, "clientId");
      await experimentsClient.displayed(experimentId, variant.id);
      await experimentsClient.won(experimentId, variant.id)
      const result = await fetch(`${config.host}/api/experiments/${experimentId}/results`, {method: "GET", headers});
      const resultJson = await result.json()
      const resultVariant = resultJson.results.find(r => r.variant.id === variant.id);
      resultVariant.displayed.should.be.equal(1)
      resultVariant.won.should.be.equal(1)
      const resultOtherVariant = resultJson.results.find(v => v.variant.id !== variant.id);
      should.equal(resultOtherVariant, undefined)
    })

  })

  describe("Test proxy", async function () {
    it("Proxy must return features / experiments / configs", async function () {
      const datas = await fetch(`http://localhost:3000/api/izanami`)
      const json = await datas.json()
      should.exist(json.features[featureId])
      should.exist(json.experiments[experimentId])
      should.exist(json.configurations[configurationId])
    })

    it("Proxy must forward won / displayed", async function (){
      await fetch(`http://localhost:3000/api/izanami/experiments/displayed?experiment=${experimentId}`, {method: "POST"})
      await fetch(`http://localhost:3000/api/izanami/experiments/won?experiment=${experimentId}`, {method: "POST"})
      const result = await fetch(`${config.host}/api/experiments/${experimentId}/results`, {method: "GET", headers});
      const resultJson = await result.json()
      const variant = await experimentsClient.variantFor(experimentId, "userId");
      const resultVariant = resultJson.results.find(r => r.variant.id === variant.id);
      resultVariant.displayed.should.be.equal(1)
      resultVariant.won.should.be.equal(1)
    })
  })
})