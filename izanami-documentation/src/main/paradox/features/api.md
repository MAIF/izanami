# APIs

@@toc { depth=3 }

## Data model 

The feature structure depends on the type of the strategy. A feature can have the following strategies: 

* `NO_STRATEGY`: active or inactive 
* `RELEASE_DATE`: active on a specific date 
* `SCRIPT`: Activation depends on the execution of a js script 
* `GLOBAL_SCRIPT`: Activation depends on the execution of a js script shared across feature


### NO_STRATEGY format 

```json
{
  "id": "basic:feature",
  "enabled": true,
  "activationStrategy": "NO_STRATEGY"
}
```

### RELEASE_DATE format

```json
{
  "id": "feature:with:date",
  "enabled": true,
  "parameters": {
    "releaseDate": "13/12/2017 10:29:04"
  },
  "activationStrategy": "RELEASE_DATE"
}
```
 
### SCRIPT format 

```json
{
  "id": "feature:with:script",
  "enabled": true,
  "parameters": {
    "script": "/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  if (context.user === 'ragnar.lodbrok@gmail.com') {\n    return enabled();\n  }\n  return disabled();\n}"
  },
  "activationStrategy": "SCRIPT"
}
```
 
### GLOBAL_SCRIPT format 

```json
{
  "id": "feature:with:global:script",
  "enabled": false,
  "parameters": {
    "ref": "project:script:ref"
  },
  "activationStrategy": "GLOBAL_SCRIPT"
}
```

## CRUD API 

Features expose a classic CRUD REST api : 

### List all 

```bash
curl -X GET \
  'http://localhost:9000/api/features?pattern=feature:*&page=1&pageSize=15' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

Will respond with a 200 status code: 

```json
{
  "results": [
    {
      "id": "feature:with:date",
      "enabled": true,
      "parameters": {
        "releaseDate": "13/12/2017 10:29:04"
      },
      "activationStrategy": "RELEASE_DATE"
    },
    {
      "id": "feature:with:script",
      "enabled": true,
      "parameters": {
        "script": "/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  if (context.user === 'ragnar.lodbrok@gmail.com') {\n    return enabled();\n  }\n  return disabled();\n}"
      },
      "activationStrategy": "SCRIPT"
    },
    {
      "id": "feature:with:global:script",
      "enabled": true,
      "parameters": {
        "ref": "project:script2"
      },
      "activationStrategy": "GLOBAL_SCRIPT"
    }
  ],
  "metadata": {
    "page": 1,
    "pageSize": 15,
    "count": 3,
    "nbPages": 1
  }
}
```

The query params are optional and the value used in this example are the default one. 

### Create a feature 

```bash
curl -XPOST \
    'http://localhost:9000/api/features' \
    -H 'Content-Type: application/json' \
    -H 'Izanami-Client-Id: xxxx' \
    -H 'Izanami-Client-Secret: xxxx' \
    -d '{ "id": "really:basic:feature", "enabled": true, "activationStrategy": "NO_STRATEGY" }' | jq  
```

Will respond with a 201 status code: 

```json
{
  "id": "really:basic:feature",
  "enabled": true,
  "activationStrategy": "NO_STRATEGY"
}
```

### Get a feature 

```bash
curl -X GET \
  'http://localhost:9000/api/features/really:basic:feature' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

Will respond with a 200 status code:

```json
{
  "id": "really:basic:feature",
  "enabled": true,
  "activationStrategy": "NO_STRATEGY"
}
```

### Update a feature

 
```bash
curl -X PUT \
  'http://localhost:9000/api/features/really:basic:feature' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  -d '{ "id": "really:basic:feature", "enabled": false, "activationStrategy": "NO_STRATEGY" }' | jq
```

Will respond with a 200 status code:

```json
{
  "id": "really:basic:feature",
  "enabled": false,
  "activationStrategy": "NO_STRATEGY"
}
```

### Delete a feature

 
```bash
curl -X DELETE \
  'http://localhost:9000/api/features/really:basic:feature' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq 
```

Will respond with a 200 status code:

```json
{
  "id": "really:basic:feature",
  "enabled": false,
  "activationStrategy": "NO_STRATEGY"
}
```


As you can see, the keys are split with `:` and the values are 

## Check features 

We can divide the feature in two kind : 

* feature without context: 
    * `NO_STRATEGY` features 
    * `RELEASE_DATE` features
* feature needing context:   
    * `SCRIPT` features 
    * `GLOBAL_SCRIPT` features
    
### Check a feature without context 


```bash
curl -X GET \
  'http://localhost:9000/api/features/really:basic:feature/check' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

Will respond with a 200 status code:

```json
{
  "active": true
}
```

### Check a feature with context

```bash
curl -X POST \
  'http://localhost:9000/api/features/feature:with:script/check' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  -d '{ "user": "ragnar.lodbrok@gmail.com" }' | jq
```

Will respond with a 200 status code:

```json
{
  "active": true
}
```


### The Tree API 

With the tree api you can check the features and get the results as tree. 
Be careful using this API because the results are not paged so be sure to use an appropriate pattern : 

Check the features without context 

```bash
curl -X GET \
  'http://localhost:9000/api/tree/features?pattern=feature:*' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

We will get the following response: 

```json
{
  "feature": {
    "simple": {
      "active": true
    },
    "with": {
      "date": {
        "active": true
      },
      "script": {
        "active": false
      },
      "global": {
        "script": {
          "active": false
        }
      }
    }
  }
}
```

Or with context: 

```bash
curl -X POST \
  'http://localhost:9000/api/tree/features?pattern=feature:*' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  -d '{ "user": "ragnar.lodbrok@gmail.com" }' \
  | jq
```

```json
{
  "feature": {
    "simple": {
      "active": true
    },
    "with": {
      "date": {
        "active": true
      },
      "script": {
        "active": false
      },
      "global": {
        "script": {
          "active": true
        }
      }
    }
  }
}
```

### List features with the active attribute without context

 
```bash
curl -X GET \
  'http://localhost:9000/api/features?pattern=feature:*&active=true' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

The result is the following 

```json
{
  "results": [
    {
      "id": "feature:simple",
      "enabled": true,
      "activationStrategy": "NO_STRATEGY",
      "active": true
    },
    {
      "id": "feature:with:date",
      "enabled": true,
      "parameters": {
        "releaseDate": "13/12/2017 10:29:04"
      },
      "activationStrategy": "RELEASE_DATE",
      "active": true
    },
    {
      "id": "feature:with:script",
      "enabled": true,
      "parameters": {
        "script": "/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  if (context.user === 'ragnar.lodbrok@gmail.com') {\n    return enabled();\n  }\n  return disabled();\n}"
      },
      "activationStrategy": "SCRIPT",
      "active": false
    },
    {
      "id": "feature:with:global:script",
      "enabled": true,
      "parameters": {
        "ref": "project:script2"
      },
      "activationStrategy": "GLOBAL_SCRIPT",
      "active": false
    }
  ],
  "metadata": {
    "page": 1,
    "pageSize": 15,
    "count": 4,
    "nbPages": 1
  }
}
```
### List features with the active attribute with context
 
```bash
curl -X POST \
  'http://localhost:9000/api/features/_checks?pattern=feature:*' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  -d '{ "user": "ragnar.lodbrok@gmail.com" }' \
  | jq
```
The result is the following 

```json
{
  "results": [
    {
      "id": "feature:simple",
      "enabled": true,
      "activationStrategy": "NO_STRATEGY",
      "active": true
    },
    {
      "id": "feature:with:date",
      "enabled": true,
      "parameters": {
        "releaseDate": "13/12/2017 10:29:04"
      },
      "activationStrategy": "RELEASE_DATE",
      "active": true
    },
    {
      "id": "feature:with:script",
      "enabled": true,
      "parameters": {
        "script": "/**\n * context:  a JSON object containing app specific value \n *           to evaluate the state of the feature\n * enabled:  a callback to mark the feature as active \n *           for this request\n * disabled: a callback to mark the feature as inactive \n *           for this request \n * http:     a http client\n */ \nfunction enabled(context, enabled, disabled, http) {\n  if (context.user === 'ragnar.lodbrok@gmail.com') {\n    return enabled();\n  }\n  return disabled();\n}"
      },
      "activationStrategy": "SCRIPT",
      "active": true
    },
    {
      "id": "feature:with:global:script",
      "enabled": true,
      "parameters": {
        "ref": "project:script2"
      },
      "activationStrategy": "GLOBAL_SCRIPT",
      "active": false
    }
  ],
  "metadata": {
    "page": 1,
    "pageSize": 15,
    "count": 4,
    "nbPages": 1
  }
}
```

