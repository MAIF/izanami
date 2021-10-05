# Node js client

## Install 

```bash
npm install izanami-node
```

## Import 

```javascript
const Izanami = require('izanami-node');
```

## Usage 

The node client expose conveniant methods to call Izanami. 

### Configure the client:
 
```javascript
const izanamicConfig = Object.assign({}, Izanami.defaultConfig, {
  host: 'http://localhost:9000',
  clientId: process.env.CLIENT_ID || 'xxxx',
  clientSecret: process.env.CLIENT_SECRET || 'xxxx',
});

// Get a configs client
const configClient = Izanami.configClient(izanamicConfig);
// Get a feature client 
const featureClient = Izanami.featureClient(izanamicConfig);
// Get a experiments client 
const experimentsClient = Izanami.experimentsClient(izanamicConfig);
```

### Configs 

### Get a config 

```javascript
configClient.config("my.config.id").then(config => {
  console.log('The config is ', config);
  tree.should.be.deep.equal({
      "value": "test"
  })
});
```
#### Get the configs tree

```javascript
configClient.configs("my.config.*").then(tree => {
  tree.should.be.deep.equal({
      "my": {
        "config": {
          "id": {
            "value": "test"
          },
          "id2": {
            "another": {
              "value": "a value"
            }
          }
        }
      }
    });
});
```
 

### Features
 
#### Check a feature

```javascript
featureClient.checkFeature("my.feature.id").then(active => {
  console.log('The feature is ', active);
});
```

Or with a context: 
 
```javascript
featureClient.checkFeature("my.feature.id", {client: "ragnard.lodbrock@gmail.com"}).then(active => {
  console.log('The feature is ', active);
});
```
#### Get the features tree 
 
```javascript
featureClient.features("my.feature.*").then(tree => {
  tree.should.be.deep.equal({
    "my": {
      "feature": {
        "id": {
          "active": true
        },
        "id2": {
          "active": false
        }
      }
    }
  });
});
```
 
Or with a context: 

```javascript
featureClient.features("my.feature.*", {client: "ragnard.lodbrock@gmail.com"}).then(tree => {
  tree.should.be.deep.equal({
    "my": {
      "feature": {
        "id": {
          "active": true
        },
        "id2": {
          "active": false
        }
      }
    }
  });
});
```

### Experiments  

#### Get an experiment
 
```javascript
experimentsClient.experiment("my.experiment.id").then(experiment => {
  //Empty json if the experiment doesn't exists 
  console.log('The experiment is ', experiment);
});
```

#### Get experiments as tree
 
```javascript
experimentsClient.experiments("my.experiment.*", "ragnard.lodbrock@gmail.com").then(tree => {
  //Empty json if the experiment doesn't exists 
  console.log('The experiment is ', experiment);
  tree.should.be.deep.equal({
    "my": {
      "experiment": {
        "id": {
          "variant": "A"
        },
        "id2": {
          "variant": "B"
        }
      }
    }
  })
});
```

#### Get a variant
 
```javascript
experimentsClient.variantFor("my.experiment.id", "ragnard.lodbrock@gmail.com").then(variant => {
  //Empty json if the variant doesn't exists 
  console.log('The variant is ', variant);
});
```

#### Mark variant displayed
 
```javascript
experimentsClient.displayed("my.experiment.id", "ragnard.lodbrock@gmail.com").then(__ => {
  console.log('The variant is marked displayed');
});
```
 
#### Mark variant won  

```javascript
experimentsClient.won("my.experiment.id", "ragnard.lodbrock@gmail.com").then(__ => {
  console.log('The variant is marked won');
});
```


## Express proxy 

You use express as a proxy to expose Izanami to the client side. 

```javascript
const app = express();

Izanami.expressProxy({
  sessionPath: '/api/izanami', // default '/api/me'
  featureClient, // Optional
  experimentsClient, // Optional
  configClient, // Optional
  app, // Express app 
  path: 'my.namespace.*' // The pattern to filter experiments, configs and features
});
```



