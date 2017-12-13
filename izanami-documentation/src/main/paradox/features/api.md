# APIs


## Data model 

The feature structure depends on the type of the strategy. A feature can have the following strategies: 

* `NO_STRATEGY`: active or inactive 
* `RELEASE_DATE`: active on a specific date 
* `SCRIPT`: Activation depends on the execution of a js script 
* `GLOBAL_SCRIPT`: Activation depends on the execution of a js script shared across feature


### NO_STRATEGY format 

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
 
### NO_STRATEGY format 



```json
{
  "id": "my:id", 
  "value": "A value"
}
```

## CRUD API 

Configs expose a classic CRUD REST api : 

### List all 

```bash
curl -X GET \
  'http://localhost:9000/api/configs?pattern=*&page=1&pageSize=15' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

Will respond with a 200 status code: 

```json
{
  "results": [
    {
      "id": "ragnar:lodbrok:email",
      "value": "{\n  \"email\": \"ragnar.lodbrok@gmail.com\"\n}"
    }
  ],
  "metadata": {
    "page": 1,
    "pageSize": 15,
    "count": 1,
    "nbPages": 1
  }
}
```

The query params are optional and the value used in this example are the default one. 

### Create a config 

```bash
curl -XPOST \
    'http://localhost:9000/api/configs' \
    -H 'Content-Type: application/json' \
    -H 'Izanami-Client-Id: xxxx' \
    -H 'Izanami-Client-Secret: xxxx' \
    -d '{ "id": "ragnar:lodbrok:city", "value": "{\"city\": \"Kattegat\"}" }' | jq  
```

Will respond with a 201 status code: 

```json
{
  "id": "ragnar:lodbrok:city",
  "value": "{\"city\": \"Kattegat\"}"
}
```

### Get a config 

```bash
curl -X GET \
  'http://localhost:9000/api/configs/ragnar:lodbrok:city' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

Will respond with a 200 status code:

```json
{
  "id": "ragnar:lodbrok:city",
  "value": "{\"city\": \"Kattegat\"}"
}
```

### Update a config

 
```bash
curl -X PUT \
  'http://localhost:9000/api/configs/ragnar:lodbrok:city' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  -d '{ "id": "ragnar:lodbrok:city", "value": "{\"city\": \"Northumbria\"}" }' | jq
```

Will respond with a 200 status code:

```json
{
  "id": "ragnar:lodbrok:city",
  "value": "{\"city\": \"Northumbria\"}"
}
```

### Delete a config

 
```bash
curl -X DELETE \
  'http://localhost:9000/api/configs/ragnar:lodbrok:city' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq 
```

Will respond with a 200 status code:

```json
{
  "id": "ragnar:lodbrok:city",
  "value": "{\"city\": \"Northumbria\"}"
}
```

## Tree API 

The tree api format the reponse as a tree. 
Be careful using this API because the results are not paged so be sure to use an appropriate pattern : 

For example if we create this datas : 

```bash
curl -XPOST \
    'http://localhost:9000/api/configs' \
    -H 'Content-Type: application/json' \
    -H 'Izanami-Client-Id: xxxx' \
    -H 'Izanami-Client-Secret: xxxx' \
    -d '{ "id": "ragnar:lodbrok:city", "value": "{\"city\": \"Kattegat\"}" }' | jq

curl -XPOST \
    'http://localhost:9000/api/configs' \
    -H 'Content-Type: application/json' \
    -H 'Izanami-Client-Id: xxxx' \
    -H 'Izanami-Client-Secret: xxxx' \
    -d '{ "id": "ragnar:lodbrok:email", "value": "\"ragnar.lodbrok@gmail.com\"" }' | jq
```

And then use the tree APIs 

```bash
curl -X GET \
  'http://localhost:9000/api/tree/configs?pattern=ragnar:*' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

We will get the following response: 

```json
{
  "ragnar": {
    "lodbrok": {
      "city": {
        "city": "Kattegat"
      },
      "email": {
        "email": "ragnar.lodbrok@gmail.com"
      }
    }
  }
}
```

As you can see, the keys are split with `:` and the json and the json values are expended into a tree representation.