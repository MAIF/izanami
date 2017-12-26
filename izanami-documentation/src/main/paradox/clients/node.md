# Node js client

```javascript
const Izanami = require('izanami');

const featClient = Izanami.featureClient(Object.assign({}, Izanami.defaultConfig, {
  clientId: "xxxx",
  clientSecret: "xxxx",
  fallbackConfig: {
    "test" : {
      "deepMerge" : {
        "key1" : {
          "active": false
        },
        "key2" : {
          "active": false
        },
        "key3" : {
          "active": false
        },
        "key4" : {
          "active": true
        }
      }
    }
  }
}));

featClient.features("test.deepMerge.*").then(tree => {
  tree.should.be.deep.equal({
    "test": {
      "deepMerge": {
        "key1": {
          "active": true
        },
        "key2": {
          "active": false
        },
        "key3": {
          "active": true
        },
        "key4": {
          "active": true
        }
      }
    }
  });
});
```
