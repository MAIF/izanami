# APIs usage

## Identification

All the APIs needs to be be identified. Identification could be done with a cookie (from the UI) or using headers (random services).  

To use header identification, you first need to generate client id and client secret using the UI : 

<img src="img/quickstart/3-apikey.png" width="50%" />

And then click add key 

<img src="img/quickstart/4-addkey.png" width="80%" />

You can change the client id and client secret if you need it. Hit the create button to finish the creation 

<img src="img/quickstart/5-createkey.png" width="80%" />

You can add restriction on keys using a pattern. 
For example, you set patterns to `mykeys:*` so the client using this credentials will only see data where the key starts with `mykeys:`.    

When it's done you can use the client id and secret using the headers `Izanami-Client-Id` and `Izanami-Client-Secret` : 

```bash

curl -X GET \
  'http://localhost:9000/api/configs/my:config' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: client' \
  -H 'Izanami-Client-Secret: client1234' --include 
 
# And the result is  
# HTTP/1.1 200 OK
# Date: Tue, 12 Dec 2017 16:06:46 GMT
# Content-Type: application/json
# Content-Length: 76
# 
# {"id":"my:config","value":"{\n  \"message\": \"Hello World!\"\n}"}
 
```   
Header names can be change, see the @ref[settings](settings/settings.md) page.  

## Error response 

The format of the errors is consistent across all Izanami APIs.  

The error format has the following structure :  

```json 
{
    "errors": [
        { 
            "message": "pattern.invalid",  
            "args": ["ragnar.lodbrok.gmail.com"] 
        }
    ], 
    "fieldErrors": {
        "obj.email": [
                { 
                    "message": "pattern.invalid",  
                    "args": ["ragnar.lodbrok.gmail.com"] 
                }
        ] 
    }
}
```

* `errors` is an array of object error. Each error has 
    * a `message` : a key corresponding to a type of error  
    * `args` : an array of additional information that can be used to build the appropriate error message 
* `fieldErrors` represent validation errors on an object. The resulting structure is an object where 
    * keys represent the path of the field of invalid object
    * value is an array of message and args as explained above 
      

## Shared config API

Go to the @ref[configs API doc](configs/api.md) 

## Feature flipping API

Go to the @ref[features API doc](features/api.md)

## Experiments (A/B testing) API

Go to the @ref[experiments API doc](experiments/api.md)

## Webhooks API

* TODO

## Users API

* TODO

## Api keys API

* TODO

## Where can I find an OpenApi ?

Go see the [open api](../swagger/swagger-ui.html)

Once you have an instance running (see @ref[Quickstart](quickstart.md)), you can find the swagger doc at `http://localhost:9000/docs/swagger-ui/index.html?url=/assets/swagger.json`.
