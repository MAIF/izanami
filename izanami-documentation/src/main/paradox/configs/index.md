# Shared Configs


@@@ index

 * [APIs](api.md)
 * [UI](ui.md)

@@@

@@toc { depth=2 }

Izanami provide the ability to share configuration between your applications and change this configuration in real time.  
The configuration is just a regular json :   

<img src="../img/configs/config.png" width="60%" />

Then just call the api : 

```bash
curl -X GET \
  'http://localhost:9000/api/configs/ragnar:lodbrok:city' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: xxxx' \
  -H 'Izanami-Client-Secret: xxxx' | jq
```

And get the config:

```json
{
  "id": "ragnar:lodbrok:city",
  "value": "{\"city\": \"Kattegat\"}"
}
```
