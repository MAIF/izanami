# Shared Configs


@@@ index

 * [APIs](api.md)
 * [UI](ui.md)

@@@


Izanami provide the ability to share configuration between your applications and change this configuration in real time.  
The configuration is just a json. The value is store as text in the database.   

To get config just call the api : 

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