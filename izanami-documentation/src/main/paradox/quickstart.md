# Quick start 


what you will need :

* JDK 11
* wget


First download the executable jar : 

```zsh
wget --quiet -O izanami.jar https://dl.bintray.com/maif/binaries/izanami.jar/latest/izanami.jar
```

And then run it 

```zsh
java -jar izanami.jar 
```

Go to http://localhost:9000 


First login! When you've just started the server, the login password is admin/admin123 (of course you can change it :) ) : 

<img src="img/quickstart/1-login.png" width="60%" />

Ok, now you've reached the home page 

<img src="img/quickstart/2-home.png" width="80%" /> 

Izanami encourage you to create a real user but let's ignore that for the moment and close the pop up. 

Now we will create an API key to use the API : 

<img src="img/quickstart/3-apikey.png" width="50%" />

And then click add key 

<img src="img/quickstart/4-addkey.png" width="80%" />

You can change the client id and client secret if you need it. Hit the create button 

<img src="img/quickstart/5-createkey.png" width="80%" />

Ok that cool now let's create a feature. Click to the "features" menu and then click to "Add item" : 

<img src="img/quickstart/6-features.png" width="80%" />

You can create a new feature with a dedicated strategy : 

<img src="img/quickstart/7-addfeature.png" width="80%" />

Let's keep this simple and choose "NO_STRATEGY". Hit the "Create feature button" and that it ! 


Now we will call the API to get the state of the feature using the client id and client secret that we've created before : 

```bash

curl -X GET \
  'http://localhost:9000/api/features/project:env:feature1/check' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: client' \
  -H 'Izanami-Client-Secret: client1234' --include 
 
# And the result is  
# HTTP/1.1 200 OK
# Date: Fri, 08 Dec 2017 10:25:53 GMT
# Content-Type: application/json
# Content-Length: 15
# 
# {"active":true}%
 
```

Now we can deactivate the feature using the toggle button : 

<img src="img/quickstart/8-deactivatefeature.png" width="80%" />

```bash

curl -X GET \
  'http://localhost:9000/api/features/project:env:feature1/check' \
  -H 'Content-Type: application/json' \
  -H 'Izanami-Client-Id: client' \
  -H 'Izanami-Client-Secret: client1234' --include 
 
# And the result is  
# HTTP/1.1 200 OK
# Date: Fri, 08 Dec 2017 10:30:09 GMT
# Content-Type: application/json
# Content-Length: 15
# 
# {"active":false}%
 
```

Ok not so hard, so now let's go deeper !