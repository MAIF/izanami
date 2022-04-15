# Identity providers

## Built in user management 

Without any configuration, Izanami uses his built in user management. You can create and manage users with the ui or with the APIs. 

The documentation is available here [User management](../ui.md#Manage users)

## Otoroshi 

You can use Otoroshi in front of izanami and delegate authentication to it. 
Otoroshi use a custom protocol to ensure secured exchange between the targeted application and Otoroshi.   


The default config is the following. You have at least to set the `sharedKey` 
(eg env variable `CLAIM_SHAREDKEY` or java system property `izanami.filter.otoroshi.sharedKey`).

```
izanami {
  filter {
    type = "Otoroshi"
    otoroshi  {
      allowedPaths = [${?OTOROSHI_FILTER_EXCLUSION}, ${?OTOROSHI_FILTER_EXCLUSION_1}, ${?OTOROSHI_FILTER_EXCLUSION_2}, ${?OTOROSHI_FILTER_EXCLUSION_3}]
      issuer = "Otoroshi"
      issuer = ${?OTOROSHI_ISSUER}
      sharedKey = "none"
      sharedKey = ${?CLAIM_SHAREDKEY}
      headerClaim = "Otoroshi-Claim"
      headerClaim = ${?FILTER_CLAIM_HEADER_NAME}
      headerRequestId = "Otoroshi-Request-Id"
      headerRequestId = ${?FILTER_REQUEST_ID_HEADER_NAME}
      headerGatewayState = "Otoroshi-State"
      headerGatewayState = ${?FILTER_GATEWAY_STATE_HEADER_NAME}
      headerGatewayStateResp = "Otoroshi-State-Resp"
      headerGatewayStateResp = ${?FILTER_GATEWAY_STATE_RESP_HEADER_NAME}
    }
  }
}
```

You can find more information about Otoroshi [here](https://maif.github.io/otoroshi/manual/) 

## Oauth 2 identity provider

To use an oauth2 identity provider we need to set the oauth2 endpoint, 
option and a way to get the user information from the oauth2 identity.

| Config property                           | Env variable                          | Description | 
|-------------------------------------------| --------------------------------------|-------------|
| `izanami.oauth2.enabled`                  | `OAUTH2_ENABLED`                      | Enable this config        |
| `izanami.oauth2.authorizeUrl`             | `OAUTH2_AUTHORIZE_URL`                | Oauth2 authorization url  |
| `izanami.oauth2.tokenUrl`                 | `OAUTH2_TOKEN_URL`                    | Oauth2 token url          |
| `izanami.oauth2.userInfoUrl`              | `OAUTH2_USER_INFO_URL`                | Oauth2 user info url      |
| `izanami.oauth2.introspectionUrl`         | `OAUTH2_INTROSPECTION_URL`            | Oauth2 introspection url  |
| `izanami.oauth2.loginUrl`                 | `OAUTH2_LOGIN_URL`                    | Oauth2 login url          |
| `izanami.oauth2.logoutUrl`                | `OAUTH2_LOGOUT_URL`                   | Oauth2 logout url         |
| `izanami.oauth2.clientId`                 | `OAUTH2_CLIENT_ID`                    | Oauth2 client id          |
| `izanami.oauth2.clientSecret`             | `OAUTH2_CLIENT_SECRET`                | Oauth2 secret if provided |
| `izanami.oauth2.scope`                    | `OAUTH2_SCOPE`                        | Oauth2 scope of the requested user info |
| `izanami.oauth2.readProfileFromToken`     | `OAUTH2_READ_FROM_TOKEN`              | Should the user be read from token |
| `izanami.oauth2.useCookie`                | `OAUTH2_USE_COOKIE`                   | Pass desc as query param |
| `izanami.oauth2.useJson`                  | `OAUTH2_USE_JSON`                     | Use json or form to post data to the server |
| `izanami.oauth2.idField`                  | `OAUTH2_ID_FIELD`                     | the path in the token to access the user id field (required field) |
| `izanami.oauth2.accessTokenField`         | `OAUTH2_ACCESS_TOKEN_FIELD`           | the path in the token to access the access token field (required field) |
| `izanami.oauth2.nameField`                | `OAUTH2_NAME_FIELD`                   | the path in the token to access the user name field (required field) |
| `izanami.oauth2.emailField`               | `OAUTH2_EMAIL_FIELD`                  | the path in the token to access the user email field (optional field) |
| `izanami.oauth2.adminField`               | `OAUTH2_ADMIN_FIELD`                  | the path in the token to access the user admin field (a boolean, false if empty) |
| `izanami.oauth2.authorizedPatternField`   | `OAUTH2_AUTHORIZED_PATTERN_FIELD`     | the path in the token to access the user authorizedPatternField field |
| `izanami.oauth2.defaultPatterns`          | `OAUTH2_DEFAULT_PATTERN`              | the default patterns if authorizedPatternField is missing |
| `izanami.oauth2.jwtVerifier.enabled`      | `OAUTH2_JWT_VERIFIER_ENABLED`         | Enable jwt verification |
| `izanami.oauth2.jwtVerifier.type`         | `OAUTH2_JWT_VERIFIER_TYPE`            | One of `hs`, `es`, `rsa`, `jwks` |
| `izanami.oauth2.jwtVerifier.size`         | `OAUTH2_JWT_VERIFIER_RSA_SIZE`        | Size of rsa `256`, `384`, `512`s |
| `izanami.oauth2.jwtVerifier.size`         | `OAUTH2_JWT_VERIFIER_HS_SIZE`         | Size of hs `256`, `384`, `512` |
| `izanami.oauth2.jwtVerifier.size`         | `OAUTH2_JWT_VERIFIER_ES_SIZE`         | Size of es `256`, `384`, `512` |
| `izanami.oauth2.jwtVerifier.secret`       | `OAUTH2_JWT_VERIFIER_HS_SECRET`       | Hs secret |
| `izanami.oauth2.jwtVerifier.publicKey`    | `OAUTH2_JWT_VERIFIER_RSA_PUBLIC_KEY`  | Rsa public key |
| `izanami.oauth2.jwtVerifier.publicKey`    | `OAUTH2_JWT_VERIFIER_ES_PUBLIC_KEY`   | ES public key |
| `izanami.oauth2.jwtVerifier.privateKey`   | `OAUTH2_JWT_VERIFIER_RSA_PRIVATE_KEY` | RSA private key |
| `izanami.oauth2.jwtVerifier.privateKey`   | `OAUTH2_JWT_VERIFIER_ES_PRIVATE_KEY`  | ES private key |
| `izanami.oauth2.jwtVerifier.url`          | `OAUTH2_JWT_VERIFIER_JWKS_URL`        | JWKS url  |
| `izanami.oauth2.jwtVerifier.headers`      | `OAUTH2_JWT_VERIFIER_JWKS_HEADERS`    | JWKS headers  |
| `izanami.oauth2.jwtVerifier.timeout`      | `OAUTH2_JWT_VERIFIER_JWKS_TIMEOUT`    | JWKS timeout call  |

The jwt modifier should be : 

### HS Algorithm

```
jwtVerifier = {
  type = "hs"
  size = 256
  secret = "your secret"
}
```

### ES Algorithm

```
jwtVerifier = {
  type = "es"
  size = 256 
  publicKey = "your key"
  privateKey = "an optional private key"
}
```

### RSA Algorithm

```
jwtVerifier = {
  type = "rsa"
  size = 256
  publicKey = "your key"
  privateKey = "an optional private key"
}
```

### JWKS Algorithm

```
jwtVerifier = {
  type = "jwks"
  url = "http://localhost:8980/auth/realms/master/protocol/openid-connect/certs"
  // Optional headers 
  headers = {
    key = value 
  }
  // An optional timeout for the api call 
  timeout = 1 second
}
```

Here is a sample to use key cloak running on `http://localhost:8980` : 

```
izanami {
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
    scope = "openid profile email name izanamiAdmin authorizedPatterns"
    jwtVerifier = {
      type = "hs"
      size = 256
      secret = "your secret"
    }
    readProfileFromToken = true
    useCookie = false
    useJson = false
    idField = "sub"
    accessTokenField = "access_token"
    nameField = "preferred_username"
    emailField = "email"
    adminField = "izanamiAdmin"
    authorizedPatternField = "authorizedPatterns"
    defaultPatterns = "*"
  }
}
```

You can find a keycloak tutorial @ref:[Here](../tutorials/oauth2.md). 