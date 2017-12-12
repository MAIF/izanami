# Use the APIs

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
Header names can be change, see the @ref[settings](configuration/settings.md) page.  

## Error response 

The format of the errors is always the same. 


## Configs API

Go to the @ref[configs API doc](configs/api.md) 

## Features API

Go to the @ref[features API doc](features/api.md)

## Experiments API

Go to the @ref[experiments API doc](experiments/api.md)

