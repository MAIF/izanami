"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[2021],{5436:(e,s,r)=>{r.r(s),r.d(s,{assets:()=>h,contentTitle:()=>l,default:()=>x,frontMatter:()=>d,metadata:()=>c,toc:()=>a});var i=r(5893),t=r(1151),n=r(1361);const d={title:"Configuring Izanami"},l=void 0,c={id:"guides/configuration",title:"Configuring Izanami",description:"Mandatory parameters",source:"@site/docs/04-guides/12-configuration.mdx",sourceDirName:"04-guides",slug:"/guides/configuration",permalink:"/izanami/docs/guides/configuration",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:12,frontMatter:{title:"Configuring Izanami"},sidebar:"tutorialSidebar",previous:{title:"Configuring mailer",permalink:"/izanami/docs/guides/mailer-configuration"},next:{title:"Webhooks",permalink:"/izanami/docs/guides/webhooks"}},h={},a=[{value:"Mandatory parameters",id:"mandatory-parameters",level:2},{value:"Secret",id:"secret",level:3},{value:"Database",id:"database",level:3},{value:"Database SSL",id:"database-ssl",level:4},{value:"HTTP server",id:"http-server",level:3},{value:"Admin account",id:"admin-account",level:3},{value:"Exposition url",id:"exposition-url",level:3},{value:"Optional parameters",id:"optional-parameters",level:2},{value:"OpenId",id:"openid",level:3},{value:"Wasmo",id:"wasmo",level:3},{value:"Various time to live",id:"various-time-to-live",level:3},{value:"Cors",id:"cors",level:3},{value:"Webhooks",id:"webhooks",level:3},{value:"SearchBar Parameter: similarity_threshold",id:"searchbar-parameter-similarity_threshold",level:3},{value:"Audit",id:"audit",level:3}];function o(e){const s={a:"a",code:"code",h2:"h2",h3:"h3",h4:"h4",li:"li",p:"p",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...(0,t.a)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(s.h2,{id:"mandatory-parameters",children:"Mandatory parameters"}),"\n",(0,i.jsx)(s.h3,{id:"secret",children:"Secret"}),"\n",(0,i.jsx)(s.p,{children:"This parameter is mandatory for production purpose.\nThis secret is used to encrypt various stuff such as token, cookies, or passwords."}),"\n",(0,i.jsx)(s.p,{children:"Your application secret must have at least 256 bits."}),"\n",(0,i.jsxs)(s.p,{children:["You can either set the ",(0,i.jsx)(s.code,{children:"IZANAMI_SECRET"})," env variable or use the ",(0,i.jsx)(s.code,{children:"app.secret"})," parameter."]}),"\n",(0,i.jsxs)(s.p,{children:["\u26a0\ufe0f If a custom secret is not set, a default will be used.\n",(0,i.jsx)(s.strong,{children:"This default is not secured"})," since it's visible in Izanami public github repository."]}),"\n",(0,i.jsx)(s.h3,{id:"database",children:"Database"}),"\n",(0,i.jsx)(s.p,{children:"You can either provide a connection URI, or indicate database name, user, host, port and so on separately."}),"\n",(0,i.jsx)(n.j,{children:(0,i.jsx)(s.p,{children:"If a connection URI is provided, all other parameters will be ignored."})}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsxs)(s.tbody,{children:[(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Uri"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_URI"}),(0,i.jsx)(s.td,{children:"app.pg.uri"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Port"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_PORT"}),(0,i.jsx)(s.td,{children:"app.pg.port"}),(0,i.jsx)(s.td,{children:"5432"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Host"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_HOST"}),(0,i.jsx)(s.td,{children:"app.pg.host"}),(0,i.jsx)(s.td,{children:"localhost"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Database name"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_DATABASE"}),(0,i.jsx)(s.td,{children:"app.pg.database"}),(0,i.jsx)(s.td,{children:"postgres"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"User"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_USER"}),(0,i.jsx)(s.td,{children:"app.pg.user"}),(0,i.jsx)(s.td,{children:"postgres"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Password"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_PASSWORD"}),(0,i.jsx)(s.td,{children:"app.pg.password"}),(0,i.jsx)(s.td,{children:"postgres"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Pool size"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_POOL_SIZE"}),(0,i.jsx)(s.td,{children:"app.pg.pool-size"}),(0,i.jsx)(s.td,{children:"20"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Connect timeout(ms)"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_CONNECT_TIMEOUT"}),(0,i.jsx)(s.td,{children:"app.pg.connect-timeout"}),(0,i.jsx)(s.td,{children:"60000"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Connection max lifetime"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_MAX_LIFETIME"}),(0,i.jsx)(s.td,{children:"app.pg.max-lifetime"}),(0,i.jsx)(s.td,{children:"0 (no maximum)"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"IDLE timeout(s)"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_IDLE_TIMEOUT"}),(0,i.jsx)(s.td,{children:"app.pg.idle-timeout"}),(0,i.jsx)(s.td,{children:"0 (no timeout)"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Log activity"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_LOG_ACTIVITY"}),(0,i.jsx)(s.td,{children:"app.pg.log-activity"}),(0,i.jsx)(s.td,{children:"false"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Pipelining limit"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_PIPELINING_LIMIT"}),(0,i.jsx)(s.td,{children:"app.pg.pipelining-limit"}),(0,i.jsx)(s.td,{children:"256"})]})]})]}),"\n",(0,i.jsx)(s.h4,{id:"database-ssl",children:"Database SSL"}),"\n",(0,i.jsx)(s.p,{children:"Izanami provides some configuration options to secure database connection with SSL"}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsxs)(s.tbody,{children:[(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"SSL"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_SSL_ENABLED"}),(0,i.jsx)(s.td,{children:"app.pg.ssl.enabled"}),(0,i.jsx)(s.td,{children:"false"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Mode"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_SSL_MODE"}),(0,i.jsx)(s.td,{children:"app.pg.ssl.mode"}),(0,i.jsx)(s.td,{children:"REQUIRE"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Trusted certtificates paths"}),(0,i.jsx)(s.td,{}),(0,i.jsx)(s.td,{children:"trusted-certs-path"}),(0,i.jsx)(s.td,{children:"[]"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Trusted certificate path"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_SSL_TRUSTED_CERT_PATH"}),(0,i.jsx)(s.td,{children:"trusted-cert-path"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Trusted certificates"}),(0,i.jsx)(s.td,{}),(0,i.jsx)(s.td,{children:"trusted-certs"}),(0,i.jsx)(s.td,{children:"[]"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Trusted certificate"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_SSL_TRUSTED_CERT"}),(0,i.jsx)(s.td,{children:"trusted-cert"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Client certificates paths"}),(0,i.jsx)(s.td,{}),(0,i.jsx)(s.td,{children:"client-certs-path"}),(0,i.jsx)(s.td,{children:"[]"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Client certificate path"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_SSL_CLIENT_CERT_PATH"}),(0,i.jsx)(s.td,{children:"client-cert-path"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Client certificates"}),(0,i.jsx)(s.td,{}),(0,i.jsx)(s.td,{children:"client-certs"}),(0,i.jsx)(s.td,{children:"[]"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Client certificate"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_SSL_CLIENT_CERT"}),(0,i.jsx)(s.td,{children:"client-cert"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Trust all certificates"}),(0,i.jsx)(s.td,{children:"IZANAMI_PG_SSL_TRUST_ALL"}),(0,i.jsx)(s.td,{children:"trust-all"}),(0,i.jsx)(s.td,{})]})]})]}),"\n",(0,i.jsx)(n.j,{children:(0,i.jsxs)(s.p,{children:["Collection variables, such as ",(0,i.jsx)(s.code,{children:"trusted-certs-path"})," can be valued throught command line with the following syntax:",(0,i.jsx)("br",{}),"\n",(0,i.jsx)(s.code,{children:"java -jar izanami.jar -Dapp.pg.ssl.trusted-certs-path.0=first_path -Dapp.pg.ssl.trusted-certs-path.1=second_path"})]})}),"\n",(0,i.jsx)(s.h3,{id:"http-server",children:"HTTP server"}),"\n",(0,i.jsx)(s.p,{children:"Since Izanami uses play framework under the hood, http server configuration rely heavily on play configuration keys."}),"\n",(0,i.jsxs)(s.p,{children:["Table above list essentials parameters, check ",(0,i.jsx)(s.a,{href:"https://www.playframework.com/documentation/2.9.x/SettingsAkkaHttp",children:"Play documentation"})," for more."]}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsx)(s.tbody,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Port"}),(0,i.jsx)(s.td,{children:"PLAY_HTTP_PORT"}),(0,i.jsx)(s.td,{children:"play.server.http.port"}),(0,i.jsx)(s.td,{children:"9000"})]})})]}),"\n",(0,i.jsx)(s.h3,{id:"admin-account",children:"Admin account"}),"\n",(0,i.jsx)(s.p,{children:"When creating on an empty database, Izanami will create a user for you."}),"\n",(0,i.jsx)(s.p,{children:"You'll have to use this user for your first login."}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsxs)(s.tbody,{children:[(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Username"}),(0,i.jsx)(s.td,{children:"IZANAMI_ADMIN_DEFAULT_USERNAME"}),(0,i.jsx)(s.td,{children:"app.admin.username"}),(0,i.jsx)(s.td,{children:"RESERVED_ADMIN_USER"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Password"}),(0,i.jsx)(s.td,{children:"IZANAMI_ADMIN_DEFAULT_PASSWORD"}),(0,i.jsx)(s.td,{children:"app.admin.password"}),(0,i.jsx)(s.td,{children:"generated (and printed in stdout)"})]})]})]}),"\n",(0,i.jsx)(s.h3,{id:"exposition-url",children:"Exposition url"}),"\n",(0,i.jsx)(s.p,{children:"Izanami needs to know on which URL it is exposed, this use for generating invitation link or query builder links."}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsx)(s.tbody,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Exposition URL"}),(0,i.jsx)(s.td,{children:"IZANAMI_EXPOSITION_URL"}),(0,i.jsx)(s.td,{children:"app.exposition.url"}),(0,i.jsx)(s.td,{children:(0,i.jsx)(i.Fragment,{children:(0,i.jsx)(s.code,{children:"http://localhost:${http.port}"})})})]})})]}),"\n",(0,i.jsx)(s.h2,{id:"optional-parameters",children:"Optional parameters"}),"\n",(0,i.jsx)(s.h3,{id:"openid",children:"OpenId"}),"\n",(0,i.jsx)(s.p,{children:"Izanami allows to set up an external openid provider."}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsxs)(s.tbody,{children:[(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Client ID"}),(0,i.jsx)(s.td,{children:"IZANAMI_OPENID_CLIENT_ID"}),(0,i.jsx)(s.td,{children:"app.openid.client-id"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Client secret"}),(0,i.jsx)(s.td,{children:"IZANAMI_OPENID_CLIENT_SECRET"}),(0,i.jsx)(s.td,{children:"app.openid.client-secret"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Authorize URL"}),(0,i.jsx)(s.td,{children:"IZANAMI_OPENID_AUTHORIZE_URL"}),(0,i.jsx)(s.td,{children:"app.openid.authorize-url"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Token URL"}),(0,i.jsx)(s.td,{children:"IZANAMI_OPENID_TOKEN_URL"}),(0,i.jsx)(s.td,{children:"app.openid.token-url"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Redirect URL"}),(0,i.jsx)(s.td,{children:"IZANAMI_OPENID_REDIRECT_URL"}),(0,i.jsx)(s.td,{children:"app.openid.redirect-url"}),(0,i.jsx)(s.td,{children:(0,i.jsx)(i.Fragment,{children:(0,i.jsx)(s.code,{children:"${app.exposition.url}/login"})})})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Scopes"}),(0,i.jsx)(s.td,{children:"IZANAMI_OPENID_SCOPES"}),(0,i.jsx)(s.td,{children:"app.openid.scopes"}),(0,i.jsx)(s.td,{children:(0,i.jsx)(i.Fragment,{children:(0,i.jsx)(s.code,{children:"openid email profile"})})})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Email field"}),(0,i.jsx)(s.td,{children:"IZANAMI_OPENID_EMAIL_FIELD"}),(0,i.jsx)(s.td,{children:"app.openid.email-field"}),(0,i.jsx)(s.td,{children:(0,i.jsx)(i.Fragment,{children:(0,i.jsx)(s.code,{children:"email"})})})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Username field"}),(0,i.jsx)(s.td,{children:"IZANAMI_OPENID_USERNAME_FIELD"}),(0,i.jsx)(s.td,{children:"app.openid.username-field"}),(0,i.jsx)(s.td,{children:(0,i.jsx)(i.Fragment,{children:(0,i.jsx)(s.code,{children:"name"})})})]})]})]}),"\n",(0,i.jsxs)(s.ul,{children:["\n",(0,i.jsxs)(s.li,{children:[(0,i.jsx)(s.code,{children:"Scopes"})," indicates which scopes should be requested when calling authorization url."]}),"\n",(0,i.jsxs)(s.li,{children:[(0,i.jsx)(s.code,{children:"Email field"})," indicates which field of id token should be used as user email"]}),"\n",(0,i.jsxs)(s.li,{children:[(0,i.jsx)(s.code,{children:"Username field"})," indicates which field of id token should be used as username"]}),"\n"]}),"\n",(0,i.jsx)(s.h3,{id:"wasmo",children:"Wasmo"}),"\n",(0,i.jsx)(s.p,{children:"If you chose to set up a WASMO instance alongside Izanami, you'll need to provide additional properties."}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsxs)(s.tbody,{children:[(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Wasmo url"}),(0,i.jsx)(s.td,{children:"IZANAMI_WASMO_URL"}),(0,i.jsx)(s.td,{children:"app.wasmo.url"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Wasmo client id"}),(0,i.jsx)(s.td,{children:"IZANAMI_WASMO_CLIENT_ID"}),(0,i.jsx)(s.td,{children:"app.wasmo.client-id"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Wasmo client secret"}),(0,i.jsx)(s.td,{children:"IZANAMI_WASMO_CLIENT_SECRET"}),(0,i.jsx)(s.td,{children:"app.wasmo.client-secret"}),(0,i.jsx)(s.td,{})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Wasm cache TTL (ms)"}),(0,i.jsx)(s.td,{children:"IZANAMI_WASM_CACHE_TTL"}),(0,i.jsx)(s.td,{children:"app.wasm.cache.ttl"}),(0,i.jsx)(s.td,{children:"60000"})]})]})]}),"\n",(0,i.jsx)(s.h3,{id:"various-time-to-live",children:"Various time to live"}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsxs)(s.tbody,{children:[(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Invitation time to live (s)"}),(0,i.jsx)(s.td,{children:"IZANAMI_INVITATIONS_TTL"}),(0,i.jsx)(s.td,{children:"app.invitations.ttl"}),(0,i.jsx)(s.td,{children:"86400 (24h)"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Session time to live (s)"}),(0,i.jsx)(s.td,{children:"IZANAMI_SESSIONS_TTL"}),(0,i.jsx)(s.td,{children:"app.sessions.ttl"}),(0,i.jsx)(s.td,{children:"3700"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Password reset request time to live (s)"}),(0,i.jsx)(s.td,{children:"IZANAMI_PASSWORD_RESET_REQUEST_TTL"}),(0,i.jsx)(s.td,{children:"app.password-reset-requests.ttl"}),(0,i.jsx)(s.td,{children:"900 (15min)"})]})]})]}),"\n",(0,i.jsx)(s.h3,{id:"cors",children:"Cors"}),"\n",(0,i.jsxs)(s.p,{children:["Izanami uses ",(0,i.jsx)(s.a,{href:"https://www.playframework.com/documentation/3.0.x/CorsFilter#Configuring-the-CORS-filter",children:"Play CORS filter"})," to handle CORS."]}),"\n",(0,i.jsxs)(s.p,{children:["Therefore, CORS is configurable using ",(0,i.jsx)(s.code,{children:"play.filters.cors"})," configuration keys."]}),"\n",(0,i.jsx)(s.p,{children:"Alternatively, Izanami re-exposes these configuration keys with below env variables :"}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{children:"Play configuration key"}),(0,i.jsx)(s.th,{children:"Environnement variable"})]})}),(0,i.jsxs)(s.tbody,{children:[(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"play.filters.cors.pathPrefixes"}),(0,i.jsx)(s.td,{children:"IZANAMI_CORS_PATH_PREFIXES"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"play.filters.cors.allowedOrigins"}),(0,i.jsx)(s.td,{children:"IZANAMI_CORS_ALLOWED_ORIGINS"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"play.filters.cors.allowedHttpMethods"}),(0,i.jsx)(s.td,{children:"IZANAMI_CORS_ALLOWED_HTTP_METHODS"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"play.filters.cors.allowedHttpHeaders"}),(0,i.jsx)(s.td,{children:"IZANAMI_CORS_ALLOWED_HTTP_HEADERS"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"play.filters.cors.exposedHeaders"}),(0,i.jsx)(s.td,{children:"IZANAMI_CORS_EXPOSED_HEADERS"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"play.filters.cors.supportsCredentials"}),(0,i.jsx)(s.td,{children:"IZANAMI_CORS_SUPPORTS_CREDENTIALS"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"play.filters.cors.preflightMaxAge"}),(0,i.jsx)(s.td,{children:"IZANAMI_CORS_PREFLIGHT_MAX_AGE"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"play.filters.cors.serveForbiddenOrigins"}),(0,i.jsx)(s.td,{children:"IZANAMI_CORS_SERVE_FORBIDDEN_ORIGINS"})]})]})]}),"\n",(0,i.jsx)(s.h3,{id:"webhooks",children:"Webhooks"}),"\n",(0,i.jsx)(s.p,{children:"Webhooks use exponential backoff algorithm to space out retries in case of failures.\nIf retry exceed max allowed retry count, it stops trying and webhook won't be called."}),"\n",(0,i.jsx)(s.p,{children:"Duration is computed as :"}),"\n",(0,i.jsx)(s.p,{children:(0,i.jsx)(s.code,{children:"retryDelay = Math.min(initialDelay * Math.pow(multiplier, currentCount), maxDelay)"})}),"\n",(0,i.jsx)(s.p,{children:"With configurable values"}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Play configuration key"}),(0,i.jsx)(s.th,{children:"Environnement variable"})]})}),(0,i.jsxs)(s.tbody,{children:[(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"max retry count"}),(0,i.jsx)(s.td,{children:"app.webhooks.retry.count"}),(0,i.jsx)(s.td,{children:"IZANAMI_WEBHOOK_RETRY_COUNT"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"initial delay (in seconds)"}),(0,i.jsx)(s.td,{children:"app.webhooks.retry.intial-delay"}),(0,i.jsx)(s.td,{children:"IZANAMI_WEBHOOK_RETRY_INITIAL_DELAY"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"max delay (in seconds)"}),(0,i.jsx)(s.td,{children:"app.webhooks.retry.max-delay"}),(0,i.jsx)(s.td,{children:"IZANAMI_WEBHOOK_RETRY_MAX_DELAY"})]}),(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"multiplier"}),(0,i.jsx)(s.td,{children:"app.webhooks.retry.multiplier"}),(0,i.jsx)(s.td,{children:"IZANAMI_WEBHOOK_RETRY_MULTIPLIER"})]})]})]}),"\n",(0,i.jsx)(s.h3,{id:"searchbar-parameter-similarity_threshold",children:"SearchBar Parameter: similarity_threshold"}),"\n",(0,i.jsx)(s.p,{children:"The similartiy_threshold parameter in Izanami is a key setting that influences the behavior of similarity-based search queries."}),"\n",(0,i.jsx)(s.p,{children:(0,i.jsx)(s.strong,{children:"Purpose"})}),"\n",(0,i.jsx)(s.p,{children:'This parameter determines how closely two strings need to match for the similarity function to recognize them as "similar".'}),"\n",(0,i.jsx)(s.p,{children:(0,i.jsx)(s.strong,{children:"Range"})}),"\n",(0,i.jsxs)(s.p,{children:["The ",(0,i.jsx)(s.a,{href:"https://www.postgresql.org/docs/current/pgtrgm.html",children:"similarity_threshold"})," is a floating-point value ranging from 0.0 to 1.0:"]}),"\n",(0,i.jsxs)(s.ul,{children:["\n",(0,i.jsx)(s.li,{children:"0.0: All strings are considered similar, allowing for maximum inclusivity in search results."}),"\n",(0,i.jsx)(s.li,{children:"1.0: Requires an exact match, providing the highest precision."}),"\n"]}),"\n",(0,i.jsx)(s.p,{children:(0,i.jsx)(s.strong,{children:"Usage"})}),"\n",(0,i.jsx)(s.p,{children:"By adjusting the threshold, you can control the sensitivity of your similarity searches:"}),"\n",(0,i.jsxs)(s.ul,{children:["\n",(0,i.jsx)(s.li,{children:"Lower values (0.2-0.4): Broader matching criteria, capturing more results that might be loosely related."}),"\n",(0,i.jsx)(s.li,{children:"Higher values (0.8-1.0): Stricter matching criteria, focusing on results that closely resemble the query."}),"\n"]}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Program argument"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsx)(s.tbody,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"Similartiy_threshold parameter"}),(0,i.jsx)(s.td,{children:"IZANAMI_SIMILARITY_THRESHOLD_PARAMETER"}),(0,i.jsx)(s.td,{children:"app.search.similarity_threshold"}),(0,i.jsx)(s.td,{children:"0.2"})]})})]}),"\n",(0,i.jsx)(s.h3,{id:"audit",children:"Audit"}),"\n",(0,i.jsx)(s.p,{children:"Izanami offers an audit log for some actions, such as feature creation/update/delete.\nTo provide this, it stores events about passed use actions. As time passes, event storage can grow very big."}),"\n",(0,i.jsx)(s.p,{children:"Retention duration for this event can be parameterized using below variable."}),"\n",(0,i.jsx)(s.p,{children:"\u26a0\ufe0f You won't be able to access audit log older than retention duration."}),"\n",(0,i.jsxs)(s.table,{children:[(0,i.jsx)(s.thead,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.th,{}),(0,i.jsx)(s.th,{children:"Play configuration key"}),(0,i.jsx)(s.th,{children:"Environnement variable"}),(0,i.jsx)(s.th,{children:"Default"})]})}),(0,i.jsx)(s.tbody,{children:(0,i.jsxs)(s.tr,{children:[(0,i.jsx)(s.td,{children:"event retention duration (in hours)"}),(0,i.jsx)(s.td,{children:"app.audit.events-hours-ttl"}),(0,i.jsx)(s.td,{children:"IZANAMI_AUDIT_EVENTS_HOURS_TTL"}),(0,i.jsx)(s.td,{children:"4344 (~6 months)"})]})})]})]})}function x(e={}){const{wrapper:s}={...(0,t.a)(),...e.components};return s?(0,i.jsx)(s,{...e,children:(0,i.jsx)(o,{...e})}):o(e)}},1361:(e,s,r)=>{r.d(s,{j:()=>d});const i={description__trivia:"description__trivia_yesz"};var t=r(4625),n=r(5893);function d(e){let{children:s}=e;return(0,n.jsxs)("div",{className:i.description__trivia,children:[(0,n.jsx)("img",{src:t.Z}),(0,n.jsx)("div",{children:s})]})}},4625:(e,s,r)=>{r.d(s,{Z:()=>i});const i=r.p+"assets/images/izanami_compressed-659337582c1667d92966e42fa1bfea3f.webp"},1151:(e,s,r)=>{r.d(s,{Z:()=>l,a:()=>d});var i=r(7294);const t={},n=i.createContext(t);function d(e){const s=i.useContext(n);return i.useMemo((function(){return"function"==typeof e?e(s):{...s,...e}}),[s,e])}function l(e){let s;return s=e.disableParentContext?"function"==typeof e.components?e.components(t):e.components||t:d(e.components),i.createElement(n.Provider,{value:s},e.children)}}}]);