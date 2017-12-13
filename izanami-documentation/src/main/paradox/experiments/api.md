# APIs

@@toc { depth=3 }

## Basic workflow 

In order to run an experimentation you have to do the following actions  

* create an experiment with variant 
* when a user interact with your application 
    * mark variant displayed for the user  
    * mark variant won for the user if the variant is a success 
      

## Data model 

An experiment has the following structure : 

```json
{
  "id": "izanami:example:button",
  "name": "My experiment",
  "description": "The description ...",
  "enabled": true,
  "variants": [
    {
      "id": "A",
      "name": "Variant A",
      "description": "Variant A is about ...",
      "traffic": 0.5
    },
    {
      "id": "B",
      "name": "Variant B",
      "description": "Variant B is about ...",
      "traffic": 0.5   
    }
  ]
}
```

## Basic CRUD operations

### List all

The query params are optional and the value used in this example are the default one.

```bash
curl -X GET \
  'http://localhost:9000/api/experiments?pattern=*&page=1&pageSize=15' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

Will respond with a 200 status code:

```json
{
  "results": [
    {
      "id": "izanami:example:button",
      "name": "My First experiment",
      "description": "See what people like the most about ...",
      "enabled": true,
      "variants": [
        {
          "id": "A",
          "name": "Variant A",
          "description": "Variant A is about ...",
          "traffic": 0.5,
          "currentPopulation": 3
        },
        {
          "id": "B",
          "name": "Variant B",
          "description": "Variant B is about ...",
          "traffic": 0.5,
          "currentPopulation": 3
        }
      ]
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


### Create an experiment

```bash
curl -X POST \
  'http://localhost:9000/api/experiments' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  -d '
    {
      "id": "izanami:test:button:color",
      "name": "Red button or Blue Button",
      "description": "Which button is the best ...",
      "enabled": true,
      "variants": [
        {
          "id": "A",
          "name": "Red button",
          "description": "Variant A is the red button",
          "traffic": 0.5        
        },
        {
          "id": "B",
          "name": "Variant B",
          "description": "Variant B is the blue button",
          "traffic": 0.5
        }
      ]
    }
  ' | jq
```

Will respond with a 201 status code:

```json
{
  "id": "izanami:test:button:color",
  "name": "Red button or Blue Button",
  "description": "Which button is the best ...",
  "enabled": true,
  "variants": [
    {
      "id": "A",
      "name": "Red button",
      "description": "Variant A is the red button",
      "traffic": 0.5
    },
    {
      "id": "B",
      "name": "Variant B",
      "description": "Variant B is the blue button",
      "traffic": 0.5
    }
  ]
}
```

### Get an experiment

```bash
curl -X GET \
  'http://localhost:9000/api/experiments/izanami:test:button:color' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  | jq
```

Will respond with a 200 status code:

```json
{
  "id": "izanami:test:button:color",
  "name": "Red button or Blue Button",
  "description": "Which button is the best ...",
  "enabled": true,
  "variants": [
    {
      "id": "A",
      "name": "Red button",
      "description": "Variant A is the red button",
      "traffic": 0.5
    },
    {
      "id": "B",
      "name": "Variant B",
      "description": "Variant B is the blue button",
      "traffic": 0.5
    }
  ]
}
```

### Update an experiment

```bash
curl -X PUT \
  'http://localhost:9000/api/experiments/izanami:test:button:color' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  -d '
      {
        "id": "izanami:test:button:color",
        "name": "Red button or Blue Button",
        "description": "Which button is the better ...",
        "enabled": true,
        "variants": [
          {
            "id": "A",
            "name": "Red button",
            "description": "Variant A is the red button",
            "traffic": 0.5        
          },
          {
            "id": "B",
            "name": "Variant B",
            "description": "Variant B is the blue button",
            "traffic": 0.5
          }
        ]
      }
    ' | jq
```

Will respond with a 200 status code:

```json
{
  "id": "izanami:test:button:color",
  "name": "Red button or Blue Button",
  "description": "Which button is the better ...",
  "enabled": true,
  "variants": [
    {
      "id": "A",
      "name": "Red button",
      "description": "Variant A is the red button",
      "traffic": 0.5
    },
    {
      "id": "B",
      "name": "Variant B",
      "description": "Variant B is the blue button",
      "traffic": 0.5
    }
  ]
}
```

### Delete an experiment


```bash
curl -X DELETE \
  'http://localhost:9000/api/experiments/izanami:test:button:color' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  | jq 
```

Will respond with a 200 status code:

```json
{
  "id": "izanami:test:button:color",
  "name": "Red button or Blue Button",
  "description": "Which button is the better ...",
  "enabled": true,
  "variants": [
    {
      "id": "A",
      "name": "Red button",
      "description": "Variant A is the red button",
      "traffic": 0.5
    },
    {
      "id": "B",
      "name": "Variant B",
      "description": "Variant B is the blue button",
      "traffic": 0.5
    }
  ]
}
```

## Operations on Variants

A variant is associated to an end user. To get the variant to display you have to provide a "clientId". 

   
### Mark variant as displayed 

The mark variant as displayed operation will generate an event of type `VariantDisplayedEvent`. 
It will also bind a variant to a user if it'as called for the first time. 
 
```bash
curl -X POST \
  'http://localhost:9000/api/experiments/izanami:test:button:color/displayed?clientId=ragnar.lodbrock@gmail.com' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq 
```


Will respond with a 200 status code:

```json
{
  "id": "izanami:test:button:color:A:ragnar.lodbrock@gmail.com:displayed:940975692945817657",
  "experimentId": "izanami:test:button:color",
  "clientId": "ragnar.lodbrock@gmail.com",
  "variant": {
    "id": "A",
    "name": "Red button",
    "description": "Variant A is the red button",
    "traffic": 0.5,
    "currentPopulation": 1
  },
  "date": "2017-12-13T17:04:22.338",
  "transformation": 0,
  "variantId": "A",
  "@type": "VariantDisplayedEvent"
}
```

The following end point return the variant associated to a user : 

```bash
curl -X GET \
  'http://localhost:9000/api/experiments/izanami:test:button:color/variant?clientId=ragnar.lodbrock@gmail.com' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  | jq
```

A response with a 200 status code is returned if the variant was marked as displayed at least once. A 404 status code is return if not :

```json
{
  "id": "A",
  "name": "Red button",
  "description": "Variant A is the red button",
  "traffic": 0.5,
  "currentPopulation": 1
}
```

### Mark variant as won 

The mark variant as won operation will generate an event of type `VariantWonEvent`. 
This action should be call if the corresponding user "validate" the variant displayed.  

```bash
curl -X POST \
  'http://localhost:9000/api/experiments/izanami:test:button:color/won?clientId=ragnar.lodbrock@gmail.com' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq 
```

Will respond with a 200 status code:

```json
{
  "id": "izanami:test:button:color:A:ragnar.lodbrock@gmail.com:won:940978218122346556",
  "experimentId": "izanami:test:button:color",
  "clientId": "ragnar.lodbrock@gmail.com",
  "variant": {
    "id": "A",
    "name": "Red button",
    "description": "Variant A is the red button",
    "traffic": 0.5,
    "currentPopulation": 1
  },
  "date": "2017-12-13T17:14:24.387",
  "transformation": 100,
  "variantId": "A",
  "@type": "VariantWonEvent"
}
```

### The tree API 

The tree api will return a set of experiment as a tree form based on a pattern. 
If the binding between a user and a variant is not done, the association will be done but no event will be generated.

```bash
curl -X GET \
  'http://localhost:9000/api/tree/experiments?pattern=izanami:test:*&clientId=ragnar.lodbrock@gmail.com' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' \
  | jq
```

Will respond with a 200 status code:

```json
{
  "izanami": {
    "test": {
      "button": {
        "color": {
          "variant": "A"
        }
      }
    }
  }
}
```
