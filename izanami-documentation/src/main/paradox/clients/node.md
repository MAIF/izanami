# Node js client

## Install 

```bash
npm install izanami 
```

## Import 

```javascript
const Izanami = require('izanami');
```


## Usage 

The node client expose conveniant methods to call Izanami. 

### Configure the client:
 
```javascript
const izanamicConfig = Object.assign({}, Izanami.defaultConfig, {
  'http://localhost:9000',
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

### Experiments  


