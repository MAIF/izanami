# Oauth2 with keycloak 

@@toc { depth=3 } 

This tutorial show how to delegate user authentication to keycloak using oauth2. 

On keycloak, you'll need to 
 * define scope for custom field 
 * set a dedicated client for Izanami 
 * add scope to client 
 * create users with custom attributes 
 * Configure Izanami 

## Running keycloak 

Just use docker : 

``` 
docker-compose -f docker-compose.oauth.yml up 
```

Go to `http://localhost:8980` and log with `izanami` / `izanami`.  
 
## Managing abilitations in Izanami 

### Create an Izanami client 

Create a new open id connect client and name it izanami. Set the root url to the url of your server. 

![Izanami client](../img/tutorial/oauth/client_izanami_1.png) 

In the settings, fill the field with the various urls of izanami. 

![Izanami client](../img/tutorial/oauth/client_izanami_2_settings.png)

Next step create some users.

### Create users 

We will create two users : 
 * one admin user 
 * one user with restricted access
 
In the users menu, add a new user 

![Users](../img/tutorial/oauth/users_all.png)
 
![Users](../img/tutorial/oauth/users_admin_1.png)

### Manage users in Izanami

After the first login action, the user will be created in Izanami. You can now manage his rights. 

![Users](../img/users/all.png)

and then edit the user 

![Users](../img/users/update_oauth_user.png)


 
## Managing abilitations in keycloak 

### Define scope 
You first need to define new scopes for 
 * `authorizedPatterns` : the pattern applied on keys that the user is able to use 
 * `admin` : a boolean to define id the user will be admin

#### Define a new scope for authorized patterns field

![Pattern scope](../img/tutorial/oauth/client_scope_patterns_1.png)
 
#### Define a mapper for authorized patterns field

Define a mapper for the `authorizedPatterns` field with a mapper type `UserAttribute` and the name of the attribute and the name in the claim. 
Here the name will be `authorizedPatterns` and the type in json will be a `string`.

![Pattern scope mapper](../img/tutorial/oauth/client_scope_patterns_2_mapper.png) 

#### Define a new scope for the admin field

![Admin scope](../img/tutorial/oauth/client_scope_admin_1.png) 

#### Define a mapper for the admin field

Define a mapper for the `admin` field with a mapper type `UserAttribute` and the name of the attribute and the name in the claim. 
Here the name will be `admin` and the type in json will be a `boolean`.

![Admin scope mapper](../img/tutorial/oauth/client_scope_admin_2_mapper.png) 

### Create an Izanami client 

Create a new open id connect client and name it izanami. Set the root url to the url of your server. 

![Izanami client](../img/tutorial/oauth/client_izanami_1.png) 

In the settings, fill the field with the various urls of izanami. 

![Izanami client](../img/tutorial/oauth/client_izanami_2_settings.png)

In the client scope panel, add the two scopes created previously. 
 
![Izanami client](../img/tutorial/oauth/izanami_client_3_scope.png) 

Next step create some users.

### Create users 

We will create two users : 
 * one admin user 
 * one user with restricted access
 
In the users menu, add a new user 

![Users](../img/tutorial/oauth/users_all.png)

#### Admin user 

Create an admin user 

![Users](../img/tutorial/oauth/users_admin_1.png)

In the attributes panel, add two attributes 

![Users](../img/tutorial/oauth/users_admin_2_attributes.png)


#### Random user 

Create an simple user 

![Users](../img/tutorial/oauth/users_admin_1.png)

In the attributes panel, add the restriction pattern 

![Users](../img/tutorial/oauth/users_admin_2_attributes.png)


## Configure izanami 

Assuming that keycloak was started on `8980` port you can set the following configuration : 

```
  oauth2 {
    enabled = true
    authorizeUrl = "http://localhost:8980/auth/realms/master/protocol/openid-connect/auth"
    tokenUrl = 	"http://localhost:8980/auth/realms/master/protocol/openid-connect/token"
    userInfoUrl = "http://localhost:8980/auth/realms/master/protocol/openid-connect/userinfo"
    introspectionUrl = 	"http://localhost:8980/auth/realms/master/protocol/openid-connect/token/introspect"
    loginUrl = "http://localhost:8980/auth/realms/master/protocol/openid-connect/auth"
    logoutUrl = "http://localhost:8980/auth/realms/master/protocol/openid-connect/logout"
    clientId = "izanami"
    clientSecret = "secret"
    scope = "openid profile email name admin authorizedPatterns"    
    readProfileFromToken = true
    useCookie = false
    useJson = false
    idField = "sub"
    accessTokenField = "access_token"
    nameField = "preferred_username"
    emailField = "email"
    adminField = "admin"
    authorizedPatternField = "authorizedPatterns"
    defaultPatterns = ""
    izanamiManagedUser = true
  }
```

Or with environment variables : 

```
OAUTH2_ENABLED="true"
OAUTH2_AUTHORIZE_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/auth"
OAUTH2_TOKEN_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/token"
OAUTH2_USER_INFO_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/userinfo"
OAUTH2_INTROSPECTION_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/token/introspect"
OAUTH2_LOGIN_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/auth"
OAUTH2_LOGOUT_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/logout"
OAUTH2_CLIENT_ID="izanami"
OAUTH2_CLIENT_SECRET="secret"
OAUTH2_SCOPE="openid profile email name admin authorizedPatterns"
OAUTH2_READ_FROM_TOKEN="true"
OAUTH2_USE_COOKIE="false"
OAUTH2_USE_JSON="false"
OAUTH2_ID_FIELD="sub"
OAUTH2_ACCESS_TOKEN_FIELD="access_token"
OAUTH2_NAME_FIELD="preferred_username"
OAUTH2_EMAIL_FIELD="email"
OAUTH2_ADMIN_FIELD="admin"
OAUTH2_AUTHORIZED_PATTERN_FIELD="authorizedPatterns"
OAUTH2_DEFAULT_PATTERN=""
OAUTH2_IZANAMI_MANAGED_USERS=true
```
    
Now if you hit the `http://localhost:9000` izanami homepage, you're redirected to the keycloak login page : 

![Admin login](../img/tutorial/oauth/izanami_login_admin.png)

Just set the admin credentials 

It's fine, the user has access to the api keys configuration, he is an admin. 

![Admin login](../img/tutorial/oauth/izanami_admin_ok.png)


Retry but this time using the johndoe account 

![Admin login](../img/tutorial/oauth/izanami_login_random.png)

The api keys configuration, he is not available anymore

![Admin login](../img/tutorial/oauth/izanami_random_ok.png)

And the user is not able to create a feature with a pattern he is not allowed 

![Admin login](../img/tutorial/oauth/izanami_random_pattern_ok.png)


## Mutual TLS authentication 


### Generate certificates

Izanami supports MTLS authentication. To do this we need to use keycloak with https et define un client certificate.

First run 

```bash
sh ./gen-cert.sh
``` 

This script will generate, the certificates needed to : 

 * use keycloak with https
 * use keycloak with mts
 * use izanami as client with mtls  

At the end, in the `keycloak-mtl` folder, you will have 
 
 * `tls.crt`and `tls.key` for the https part of keycloak
 * `ca-client.bundle` for the mtls part of keyclaok 
 * `server.pem` and `client.p12` for izanami

### Keycloak configuration 

Then run keycloak : 

```
docker-compose -f docker-compose.oauth.yml up  
```

in the docker-compose, this setting enable https :

```yaml
volumes:
  - ./keycloak-tls:/etc/x509/https
```
because, the docker image track `/etc/x509/https/tsl.crt` and `/etc/x509/https/tsl.key`. 

and this settings define a client ca bundle for mtls : 

```yaml
X509_CA_BUNDLE: /etc/x509/https/ca-client.bundle
```  

Now we have a keycloak server running with https and mtls configured, you can open the `https://localhost:8943` in your browser.

You can update the previous config 
 
 * `Access Type`: should be `confidential`. This display `Service Accounts Enabled` and `Authorization Enabled`
 * `Service Accounts Enabled` should be checked 
 * `Authorization Enabled` should be checked

![MTLS](../img/tutorial/oauth/mtls1.png)
 
The `Credentials` menu appears. 

![MTLS](../img/tutorial/oauth/mtls2.png)

 * `Client Authenticator`: choose the `X509 Certificate` option
 * `Subject DN`: Set the DN with `CN=izanami`
 
This is now done for the keycloak part! 

Now we need to configure the Izanami part. 

### Izanami configuration

In the oauth configuration part of Izanami, we need to configure the mtls part, pointing to the files generated previously : 

```hocon
oauth2 {
  # ... 
  mtls = {
    enabled = true
    ssl-config {
      #...
      trustManager {
        stores = [
          {
            path: "./keycloak-tls/server.pem"
            type: "PEM"
          }
        ]      
      }
      keyManager {
        stores = [
          { path= "./keycloak-tls/client.p12"
            password="izanami"
            type= "PKCS12"}
        ]
      }
    } 
  }
}
```

You can find more option about the SSL config on this link: https://lightbend.github.io/ssl-config/index.html. 

That it ! 

