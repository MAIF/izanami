"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[8298],{2927:(e,n,i)=>{i.r(n),i.d(n,{assets:()=>c,contentTitle:()=>r,default:()=>h,frontMatter:()=>s,metadata:()=>l,toc:()=>d});var t=i(5893),a=i(1151);const s={title:"Oauth2 with keycloak"},r=void 0,l={id:"tutorials/oauth2",title:"Oauth2 with keycloak",description:"This tutorial show how to delegate user authentication to keycloak using oauth2.",source:"@site/v1/18-tutorials/02-oauth2.mdx",sourceDirName:"18-tutorials",slug:"/tutorials/oauth2",permalink:"/izanami/v1/tutorials/oauth2",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:2,frontMatter:{title:"Oauth2 with keycloak"},sidebar:"defaultSidebar",previous:{title:"Spring / React tutorial",permalink:"/izanami/v1/tutorials/spring"},next:{title:"Performances",permalink:"/izanami/v1/performances"}},c={},d=[{value:"Running keycloak",id:"running-keycloak",level:2},{value:"Managing abilitations in Izanami",id:"managing-abilitations-in-izanami",level:2},{value:"Create an Izanami client",id:"create-an-izanami-client",level:3},{value:"Create users",id:"create-users",level:3},{value:"Manage users in Izanami",id:"manage-users-in-izanami",level:3},{value:"Managing abilitations in keycloak",id:"managing-abilitations-in-keycloak",level:2},{value:"Define scope",id:"define-scope",level:3},{value:"Define a new scope for authorized patterns field",id:"define-a-new-scope-for-authorized-patterns-field",level:4},{value:"Define a mapper for authorized patterns field",id:"define-a-mapper-for-authorized-patterns-field",level:4},{value:"Define a new scope for the admin field",id:"define-a-new-scope-for-the-admin-field",level:4},{value:"Define a mapper for the admin field",id:"define-a-mapper-for-the-admin-field",level:4},{value:"Create an Izanami client",id:"create-an-izanami-client-1",level:3},{value:"Create users",id:"create-users-1",level:3},{value:"Admin user",id:"admin-user",level:4},{value:"Random user",id:"random-user",level:4},{value:"Configure izanami",id:"configure-izanami",level:2},{value:"Mutual TLS authentication",id:"mutual-tls-authentication",level:2},{value:"Generate certificates",id:"generate-certificates",level:3},{value:"Keycloak configuration",id:"keycloak-configuration",level:3},{value:"Izanami configuration",id:"izanami-configuration",level:3}];function o(e){const n={a:"a",code:"code",h2:"h2",h3:"h3",h4:"h4",img:"img",li:"li",p:"p",pre:"pre",ul:"ul",...(0,a.a)(),...e.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(n.p,{children:"This tutorial show how to delegate user authentication to keycloak using oauth2."}),"\n",(0,t.jsx)(n.p,{children:"On keycloak, you'll need to"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"define scope for custom field"}),"\n",(0,t.jsx)(n.li,{children:"set a dedicated client for Izanami"}),"\n",(0,t.jsx)(n.li,{children:"add scope to client"}),"\n",(0,t.jsx)(n.li,{children:"create users with custom attributes"}),"\n",(0,t.jsx)(n.li,{children:"Configure Izanami"}),"\n"]}),"\n",(0,t.jsx)(n.h2,{id:"running-keycloak",children:"Running keycloak"}),"\n",(0,t.jsx)(n.p,{children:"Just use docker :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"docker-compose -f docker-compose.oauth.yml up \n"})}),"\n",(0,t.jsxs)(n.p,{children:["Go to ",(0,t.jsx)(n.code,{children:"http://localhost:8980"})," and log with ",(0,t.jsx)(n.code,{children:"izanami"})," / ",(0,t.jsx)(n.code,{children:"izanami"}),"."]}),"\n",(0,t.jsx)(n.h2,{id:"managing-abilitations-in-izanami",children:"Managing abilitations in Izanami"}),"\n",(0,t.jsx)(n.h3,{id:"create-an-izanami-client",children:"Create an Izanami client"}),"\n",(0,t.jsx)(n.p,{children:"Create a new open id connect client and name it izanami. Set the root url to the url of your server."}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Izanami client",src:i(8013).Z+"",width:"1696",height:"742"})}),"\n",(0,t.jsx)(n.p,{children:"In the settings, fill the field with the various urls of izanami."}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Izanami client",src:i(4868).Z+"",width:"1994",height:"1524"})}),"\n",(0,t.jsx)(n.p,{children:"Next step create some users."}),"\n",(0,t.jsx)(n.h3,{id:"create-users",children:"Create users"}),"\n",(0,t.jsx)(n.p,{children:"We will create two users :"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"one admin user"}),"\n",(0,t.jsx)(n.li,{children:"one user with restricted access"}),"\n"]}),"\n",(0,t.jsx)(n.p,{children:"In the users menu, add a new user"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(5261).Z+"",width:"2364",height:"446"})}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(5270).Z+"",width:"1818",height:"1120"})}),"\n",(0,t.jsx)(n.h3,{id:"manage-users-in-izanami",children:"Manage users in Izanami"}),"\n",(0,t.jsx)(n.p,{children:"After the first login action, the user will be created in Izanami. You can now manage his rights."}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(7516).Z+"",width:"2876",height:"794"})}),"\n",(0,t.jsx)(n.p,{children:"and then edit the user"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(345).Z+"",width:"2876",height:"942"})}),"\n",(0,t.jsx)(n.h2,{id:"managing-abilitations-in-keycloak",children:"Managing abilitations in keycloak"}),"\n",(0,t.jsx)(n.h3,{id:"define-scope",children:"Define scope"}),"\n",(0,t.jsx)(n.p,{children:"You first need to define new scopes for"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"authorizedPatterns"})," : the pattern applied on keys that the user is able to use"]}),"\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"admin"})," : a boolean to define id the user will be admin"]}),"\n"]}),"\n",(0,t.jsx)(n.h4,{id:"define-a-new-scope-for-authorized-patterns-field",children:"Define a new scope for authorized patterns field"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Pattern scope",src:i(8265).Z+"",width:"1690",height:"964"})}),"\n",(0,t.jsx)(n.h4,{id:"define-a-mapper-for-authorized-patterns-field",children:"Define a mapper for authorized patterns field"}),"\n",(0,t.jsxs)(n.p,{children:["Define a mapper for the ",(0,t.jsx)(n.code,{children:"authorizedPatterns"})," field with a mapper type ",(0,t.jsx)(n.code,{children:"UserAttribute"})," and the name of the attribute and the name in the claim.\nHere the name will be ",(0,t.jsx)(n.code,{children:"authorizedPatterns"})," and the type in json will be a ",(0,t.jsx)(n.code,{children:"string"}),"."]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Pattern scope mapper",src:i(4209).Z+"",width:"1712",height:"1274"})}),"\n",(0,t.jsx)(n.h4,{id:"define-a-new-scope-for-the-admin-field",children:"Define a new scope for the admin field"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Admin scope",src:i(8086).Z+"",width:"1674",height:"1052"})}),"\n",(0,t.jsx)(n.h4,{id:"define-a-mapper-for-the-admin-field",children:"Define a mapper for the admin field"}),"\n",(0,t.jsxs)(n.p,{children:["Define a mapper for the ",(0,t.jsx)(n.code,{children:"admin"})," field with a mapper type ",(0,t.jsx)(n.code,{children:"UserAttribute"})," and the name of the attribute and the name in the claim.\nHere the name will be ",(0,t.jsx)(n.code,{children:"admin"})," and the type in json will be a ",(0,t.jsx)(n.code,{children:"boolean"}),"."]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Admin scope mapper",src:i(3373).Z+"",width:"1672",height:"1358"})}),"\n",(0,t.jsx)(n.h3,{id:"create-an-izanami-client-1",children:"Create an Izanami client"}),"\n",(0,t.jsx)(n.p,{children:"Create a new open id connect client and name it izanami. Set the root url to the url of your server."}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Izanami client",src:i(8013).Z+"",width:"1696",height:"742"})}),"\n",(0,t.jsx)(n.p,{children:"In the settings, fill the field with the various urls of izanami."}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Izanami client",src:i(4868).Z+"",width:"1994",height:"1524"})}),"\n",(0,t.jsx)(n.p,{children:"In the client scope panel, add the two scopes created previously."}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Izanami client",src:i(6499).Z+"",width:"2022",height:"988"})}),"\n",(0,t.jsx)(n.p,{children:"Next step create some users."}),"\n",(0,t.jsx)(n.h3,{id:"create-users-1",children:"Create users"}),"\n",(0,t.jsx)(n.p,{children:"We will create two users :"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"one admin user"}),"\n",(0,t.jsx)(n.li,{children:"one user with restricted access"}),"\n"]}),"\n",(0,t.jsx)(n.p,{children:"In the users menu, add a new user"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(5261).Z+"",width:"2364",height:"446"})}),"\n",(0,t.jsx)(n.h4,{id:"admin-user",children:"Admin user"}),"\n",(0,t.jsx)(n.p,{children:"Create an admin user"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(5270).Z+"",width:"1818",height:"1120"})}),"\n",(0,t.jsx)(n.p,{children:"In the attributes panel, add two attributes"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(5623).Z+"",width:"2382",height:"662"})}),"\n",(0,t.jsx)(n.h4,{id:"random-user",children:"Random user"}),"\n",(0,t.jsx)(n.p,{children:"Create an simple user"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(5270).Z+"",width:"1818",height:"1120"})}),"\n",(0,t.jsx)(n.p,{children:"In the attributes panel, add the restriction pattern"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Users",src:i(5623).Z+"",width:"2382",height:"662"})}),"\n",(0,t.jsx)(n.h2,{id:"configure-izanami",children:"Configure izanami"}),"\n",(0,t.jsxs)(n.p,{children:["Assuming that keycloak was started on ",(0,t.jsx)(n.code,{children:"8980"})," port you can set the following configuration :"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:'  oauth2 {\n    enabled = true\n    authorizeUrl = "http://localhost:8980/auth/realms/master/protocol/openid-connect/auth"\n    tokenUrl = \t"http://localhost:8980/auth/realms/master/protocol/openid-connect/token"\n    userInfoUrl = "http://localhost:8980/auth/realms/master/protocol/openid-connect/userinfo"\n    introspectionUrl = \t"http://localhost:8980/auth/realms/master/protocol/openid-connect/token/introspect"\n    loginUrl = "http://localhost:8980/auth/realms/master/protocol/openid-connect/auth"\n    logoutUrl = "http://localhost:8980/auth/realms/master/protocol/openid-connect/logout"\n    clientId = "izanami"\n    clientSecret = "secret"\n    scope = "openid profile email name admin authorizedPatterns"    \n    readProfileFromToken = true\n    useCookie = false\n    useJson = false\n    idField = "sub"\n    accessTokenField = "access_token"\n    nameField = "preferred_username"\n    emailField = "email"\n    adminField = "admin"\n    authorizedPatternField = "authorizedPatterns"\n    defaultPatterns = ""\n    izanamiManagedUser = true\n  }\n'})}),"\n",(0,t.jsx)(n.p,{children:"Or with environment variables :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:'OAUTH2_ENABLED="true"\nOAUTH2_AUTHORIZE_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/auth"\nOAUTH2_TOKEN_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/token"\nOAUTH2_USER_INFO_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/userinfo"\nOAUTH2_INTROSPECTION_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/token/introspect"\nOAUTH2_LOGIN_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/auth"\nOAUTH2_LOGOUT_URL="http://localhost:8980/auth/realms/master/protocol/openid-connect/logout"\nOAUTH2_CLIENT_ID="izanami"\nOAUTH2_CLIENT_SECRET="secret"\nOAUTH2_SCOPE="openid profile email name admin authorizedPatterns"\nOAUTH2_READ_FROM_TOKEN="true"\nOAUTH2_USE_COOKIE="false"\nOAUTH2_USE_JSON="false"\nOAUTH2_ID_FIELD="sub"\nOAUTH2_ACCESS_TOKEN_FIELD="access_token"\nOAUTH2_NAME_FIELD="preferred_username"\nOAUTH2_EMAIL_FIELD="email"\nOAUTH2_ADMIN_FIELD="admin"\nOAUTH2_AUTHORIZED_PATTERN_FIELD="authorizedPatterns"\nOAUTH2_DEFAULT_PATTERN=""\nOAUTH2_IZANAMI_MANAGED_USERS=true\n'})}),"\n",(0,t.jsxs)(n.p,{children:["Now if you hit the ",(0,t.jsx)(n.code,{children:"http://localhost:9000"})," izanami homepage, you're redirected to the keycloak login page :"]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Admin login",src:i(4786).Z+"",width:"1412",height:"1074"})}),"\n",(0,t.jsx)(n.p,{children:"Just set the admin credentials"}),"\n",(0,t.jsx)(n.p,{children:"It's fine, the user has access to the api keys configuration, he is an admin."}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Admin login",src:i(5349).Z+"",width:"2878",height:"1522"})}),"\n",(0,t.jsx)(n.p,{children:"Retry but this time using the johndoe account"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Admin login",src:i(8710).Z+"",width:"1282",height:"1112"})}),"\n",(0,t.jsx)(n.p,{children:"The api keys configuration, he is not available anymore"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Admin login",src:i(4917).Z+"",width:"2878",height:"1340"})}),"\n",(0,t.jsx)(n.p,{children:"And the user is not able to create a feature with a pattern he is not allowed"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Admin login",src:i(4273).Z+"",width:"2878",height:"1250"})}),"\n",(0,t.jsx)(n.h2,{id:"mutual-tls-authentication",children:"Mutual TLS authentication"}),"\n",(0,t.jsx)(n.h3,{id:"generate-certificates",children:"Generate certificates"}),"\n",(0,t.jsx)(n.p,{children:"Izanami supports MTLS authentication. To do this we need to use keycloak with https and define a client certificate."}),"\n",(0,t.jsx)(n.p,{children:"First run"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-bash",children:"sh ./gen-cert.sh\n"})}),"\n",(0,t.jsx)(n.p,{children:"This script will generate, the certificates needed to :"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"use keycloak with https"}),"\n",(0,t.jsx)(n.li,{children:"use keycloak with mts"}),"\n",(0,t.jsx)(n.li,{children:"use izanami as client with mtls"}),"\n"]}),"\n",(0,t.jsxs)(n.p,{children:["At the end, in the ",(0,t.jsx)(n.code,{children:"keycloak-mtl"})," folder, you will have"]}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"tls.crt"}),"and ",(0,t.jsx)(n.code,{children:"tls.key"})," for the https part of keycloak"]}),"\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"ca-client.bundle"})," for the mtls part of keyclaok"]}),"\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"server.pem"})," and ",(0,t.jsx)(n.code,{children:"client.p12"})," for izanami"]}),"\n"]}),"\n",(0,t.jsx)(n.h3,{id:"keycloak-configuration",children:"Keycloak configuration"}),"\n",(0,t.jsx)(n.p,{children:"Then run keycloak :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"docker-compose -f docker-compose.oauth.yml up  \n"})}),"\n",(0,t.jsx)(n.p,{children:"in the docker-compose, this setting enable https :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-yaml",children:"volumes:\n  - ./keycloak-tls:/etc/x509/https\n"})}),"\n",(0,t.jsxs)(n.p,{children:["because, the docker image track ",(0,t.jsx)(n.code,{children:"/etc/x509/https/tsl.crt"})," and ",(0,t.jsx)(n.code,{children:"/etc/x509/https/tsl.key"}),"."]}),"\n",(0,t.jsx)(n.p,{children:"and this settings define a client ca bundle for mtls :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-yaml",children:"X509_CA_BUNDLE: /etc/x509/https/ca-client.bundle\n"})}),"\n",(0,t.jsxs)(n.p,{children:["Now we have a keycloak server running with https and mtls configured, you can open the ",(0,t.jsx)(n.code,{children:"https://localhost:8943"})," in your browser."]}),"\n",(0,t.jsx)(n.p,{children:"You can update the previous config"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"Access Type"}),": should be ",(0,t.jsx)(n.code,{children:"confidential"}),". This display ",(0,t.jsx)(n.code,{children:"Service Accounts Enabled"})," and ",(0,t.jsx)(n.code,{children:"Authorization Enabled"})]}),"\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"Service Accounts Enabled"})," should be checked"]}),"\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"Authorization Enabled"})," should be checked"]}),"\n"]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"MTLS",src:i(1235).Z+"",width:"2874",height:"1326"})}),"\n",(0,t.jsxs)(n.p,{children:["The ",(0,t.jsx)(n.code,{children:"Credentials"})," menu appears."]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"MTLS",src:i(9800).Z+"",width:"1852",height:"784"})}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"Client Authenticator"}),": choose the ",(0,t.jsx)(n.code,{children:"X509 Certificate"})," option"]}),"\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"Subject DN"}),": Set the DN with ",(0,t.jsx)(n.code,{children:"CN=izanami"})]}),"\n"]}),"\n",(0,t.jsx)(n.p,{children:"This is now done for the keycloak part!"}),"\n",(0,t.jsx)(n.p,{children:"Now we need to configure the Izanami part."}),"\n",(0,t.jsx)(n.h3,{id:"izanami-configuration",children:"Izanami configuration"}),"\n",(0,t.jsx)(n.p,{children:"In the oauth configuration part of Izanami, we need to configure the mtls part, pointing to the files generated previously :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-hocon",children:'oauth2 {\n  # ... \n  mtls = {\n    enabled = true\n    ssl-config {\n      #...\n      trustManager {\n        stores = [\n          {\n            path: "./keycloak-tls/server.pem"\n            type: "PEM"\n          }\n        ]      \n      }\n      keyManager {\n        stores = [\n          { path= "./keycloak-tls/client.p12"\n            password="izanami"\n            type= "PKCS12"}\n        ]\n      }\n    } \n  }\n}\n'})}),"\n",(0,t.jsxs)(n.p,{children:["You can find more option about the SSL config on this link: ",(0,t.jsx)(n.a,{href:"https://lightbend.github.io/ssl-config/index.html",children:"https://lightbend.github.io/ssl-config/index.html"}),"."]}),"\n",(0,t.jsx)(n.p,{children:"That it !"})]})}function h(e={}){const{wrapper:n}={...(0,a.a)(),...e.components};return n?(0,t.jsx)(n,{...e,children:(0,t.jsx)(o,{...e})}):o(e)}},8013:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/client_izanami_1-269cadf23f8d3c46d4b30a96da66763e.png"},4868:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/client_izanami_2_settings-e6faeee328441a187fe398b20a5a27b0.png"},8086:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/client_scope_admin_1-0be10a69bcd2bad04c0329c696a057cd.png"},3373:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/client_scope_admin_2_mapper-ebf8a325f896bb81a94fa5780c868986.png"},8265:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/client_scope_patterns_1-11db08a97cc392f7090c86441cd64efd.png"},4209:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/client_scope_patterns_2_mapper-1fba137563fbd5476382ed0c5ae251b1.png"},5349:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/izanami_admin_ok-0540fb800c48460cae5be4c7affb081b.png"},6499:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/izanami_client_3_scope-61d6e175cf28119cd1349d041d96c1e3.png"},4786:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/izanami_login_admin-059ab2962ccee42031a0dfb0130e298f.png"},8710:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/izanami_login_random-5e5dfc1b4e646740636a9f91d25bc52e.png"},4917:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/izanami_random_ok-7c0f4c250bd77d6ad949f86a1627a785.png"},4273:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/izanami_random_pattern_ok-30caa11b620698be65d2da2c412054ce.png"},1235:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/mtls1-f0df8ed98bb4b8ab4a7d6f38ad75514c.png"},9800:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/mtls2-cc1af64630dd12345f5afa36cd3f2523.png"},5270:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/users_admin_1-50ec8192135ec08808768942874b80a1.png"},5623:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/users_admin_2_attributes-6179adbe53977b87f6fcf08b829aad05.png"},5261:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/users_all-ccd8474b8501855b016c22ba293ee6b1.png"},7516:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/all-03ae667f510b97add959ee96af434aa3.png"},345:(e,n,i)=>{i.d(n,{Z:()=>t});const t=i.p+"assets/images/update_oauth_user-e5a31e5042c29cd2e78e14df822d11ee.png"},1151:(e,n,i)=>{i.d(n,{Z:()=>l,a:()=>r});var t=i(7294);const a={},s=t.createContext(a);function r(e){const n=t.useContext(s);return t.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function l(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:r(e.components),t.createElement(s.Provider,{value:n},e.children)}}}]);