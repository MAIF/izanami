"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[82],{8937:(e,i,s)=>{s.r(i),s.d(i,{assets:()=>c,contentTitle:()=>d,default:()=>o,frontMatter:()=>t,metadata:()=>l,toc:()=>h});var r=s(5893),n=s(1151);const t={title:"Configuring Izanami"},d=void 0,l={id:"guides/configuration",title:"Configuring Izanami",description:"Mandatory parameters",source:"@site/docs/04-guides/11-configuration.mdx",sourceDirName:"04-guides",slug:"/guides/configuration",permalink:"/izanami/docs/guides/configuration",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:11,frontMatter:{title:"Configuring Izanami"},sidebar:"tutorialSidebar",previous:{title:"Configuring mailer",permalink:"/izanami/docs/guides/mailer-configuration"},next:{title:"Webhooks",permalink:"/izanami/docs/guides/webhooks"}},c={},h=[{value:"Mandatory parameters",id:"mandatory-parameters",level:2},{value:"Secret",id:"secret",level:3},{value:"Database",id:"database",level:3},{value:"Admin account",id:"admin-account",level:3},{value:"Exposition url",id:"exposition-url",level:3},{value:"Optional parameters",id:"optional-parameters",level:2},{value:"OpenId",id:"openid",level:3},{value:"Wasmo",id:"wasmo",level:3},{value:"Various time to live",id:"various-time-to-live",level:3},{value:"Cors",id:"cors",level:3},{value:"Webhooks",id:"webhooks",level:3}];function a(e){const i={a:"a",code:"code",h2:"h2",h3:"h3",li:"li",p:"p",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...(0,n.a)(),...e.components};return(0,r.jsxs)(r.Fragment,{children:[(0,r.jsx)(i.h2,{id:"mandatory-parameters",children:"Mandatory parameters"}),"\n",(0,r.jsx)(i.h3,{id:"secret",children:"Secret"}),"\n",(0,r.jsx)(i.p,{children:"This parameter is mandatory for production purpose.\nThis secret is used to encrypt various stuff such as token, cookies, or passwords."}),"\n",(0,r.jsx)(i.p,{children:"Your application secret must have at least 256 bits."}),"\n",(0,r.jsxs)(i.p,{children:["You can either set the ",(0,r.jsx)(i.code,{children:"IZANAMI_SECRET"})," env variable or use the ",(0,r.jsx)(i.code,{children:"app.secret"})," parameter."]}),"\n",(0,r.jsxs)(i.p,{children:["\u26a0\ufe0f If a custom secret is not set, a default will be used.\n",(0,r.jsx)(i.strong,{children:"This default is not secured"})," since it's visible in Izanami public github repository."]}),"\n",(0,r.jsx)(i.h3,{id:"database",children:"Database"}),"\n",(0,r.jsx)(i.p,{children:"You can either provide a connection URI, or indicate database name, user, host, port and so on separately."}),"\n",(0,r.jsxs)(i.table,{children:[(0,r.jsx)(i.thead,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.th,{}),(0,r.jsx)(i.th,{children:"Environnement variable"}),(0,r.jsx)(i.th,{children:"Program argument"}),(0,r.jsx)(i.th,{children:"Default"})]})}),(0,r.jsxs)(i.tbody,{children:[(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Uri"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_URI"}),(0,r.jsx)(i.td,{children:"app.pg.uri"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Port"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_PORT"}),(0,r.jsx)(i.td,{children:"app.pg.port"}),(0,r.jsx)(i.td,{children:"5432"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Host"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_HOST"}),(0,r.jsx)(i.td,{children:"app.pg.host"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Database name"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_DATABASE"}),(0,r.jsx)(i.td,{children:"app.pg.database"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"User"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_USER"}),(0,r.jsx)(i.td,{children:"app.pg.user"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Password"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_PASSWORD"}),(0,r.jsx)(i.td,{children:"app.pg.password"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Pool size"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_POOL_SIZE"}),(0,r.jsx)(i.td,{children:"app.pg.pool-size"}),(0,r.jsx)(i.td,{children:"20"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Connect timeout(ms)"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_CONNECT_TIMEOUT"}),(0,r.jsx)(i.td,{children:"app.pg.connect-timeout"}),(0,r.jsx)(i.td,{children:"60000"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"IDLE timeout(s)"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_IDLE_TIMEOUT"}),(0,r.jsx)(i.td,{children:"app.pg.idle-timeout"}),(0,r.jsx)(i.td,{children:"0 (no timeout)"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Log activity"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_LOG_ACTIVITY"}),(0,r.jsx)(i.td,{children:"app.pg.log-activity"}),(0,r.jsx)(i.td,{children:"false"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Pipelining limit"}),(0,r.jsx)(i.td,{children:"IZANAMI_PG_PIPELINING_LIMIT"}),(0,r.jsx)(i.td,{children:"app.pg.pipelining-limit"}),(0,r.jsx)(i.td,{children:"256"})]})]})]}),"\n",(0,r.jsx)(i.h3,{id:"admin-account",children:"Admin account"}),"\n",(0,r.jsx)(i.p,{children:"When creating on an empty database, Izanami will create a user for you."}),"\n",(0,r.jsx)(i.p,{children:"You'll have to use this user for your first login."}),"\n",(0,r.jsxs)(i.table,{children:[(0,r.jsx)(i.thead,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.th,{}),(0,r.jsx)(i.th,{children:"Environnement variable"}),(0,r.jsx)(i.th,{children:"Program argument"}),(0,r.jsx)(i.th,{children:"Default"})]})}),(0,r.jsxs)(i.tbody,{children:[(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Username"}),(0,r.jsx)(i.td,{children:"IZANAMI_ADMIN_DEFAULT_USERNAME"}),(0,r.jsx)(i.td,{children:"app.admin.username"}),(0,r.jsx)(i.td,{children:"RESERVED_ADMIN_USER"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Password"}),(0,r.jsx)(i.td,{children:"IZANAMI_ADMIN_DEFAULT_PASSWORD"}),(0,r.jsx)(i.td,{children:"app.admin.password"}),(0,r.jsx)(i.td,{children:"generated (and printed in stdout)"})]})]})]}),"\n",(0,r.jsx)(i.h3,{id:"exposition-url",children:"Exposition url"}),"\n",(0,r.jsx)(i.p,{children:"Izanami needs to know on which URL it is exposed, this use for generating invitation link or query builder links."}),"\n",(0,r.jsxs)(i.table,{children:[(0,r.jsx)(i.thead,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.th,{}),(0,r.jsx)(i.th,{children:"Environnement variable"}),(0,r.jsx)(i.th,{children:"Program argument"}),(0,r.jsx)(i.th,{children:"Default"})]})}),(0,r.jsx)(i.tbody,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Exposition URL"}),(0,r.jsx)(i.td,{children:"IZANAMI_EXPOSITION_URL"}),(0,r.jsx)(i.td,{children:"app.exposition.url"}),(0,r.jsx)(i.td,{children:(0,r.jsx)(r.Fragment,{children:(0,r.jsx)(i.code,{children:"http://localhost:${http.port}"})})})]})})]}),"\n",(0,r.jsx)(i.h2,{id:"optional-parameters",children:"Optional parameters"}),"\n",(0,r.jsx)(i.h3,{id:"openid",children:"OpenId"}),"\n",(0,r.jsx)(i.p,{children:"Izanami allows to set up an external openid provider."}),"\n",(0,r.jsxs)(i.table,{children:[(0,r.jsx)(i.thead,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.th,{}),(0,r.jsx)(i.th,{children:"Environnement variable"}),(0,r.jsx)(i.th,{children:"Program argument"}),(0,r.jsx)(i.th,{children:"Default"})]})}),(0,r.jsxs)(i.tbody,{children:[(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Client ID"}),(0,r.jsx)(i.td,{children:"IZANAMI_OPENID_CLIENT_ID"}),(0,r.jsx)(i.td,{children:"app.openid.client-id"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Client secret"}),(0,r.jsx)(i.td,{children:"IZANAMI_OPENID_CLIENT_SECRET"}),(0,r.jsx)(i.td,{children:"app.openid.client-secret"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Authorize URL"}),(0,r.jsx)(i.td,{children:"IZANAMI_OPENID_AUTHORIZE_URL"}),(0,r.jsx)(i.td,{children:"app.openid.authorize-url"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Token URL"}),(0,r.jsx)(i.td,{children:"IZANAMI_OPENID_TOKEN_URL"}),(0,r.jsx)(i.td,{children:"app.openid.token-url"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Redirect URL"}),(0,r.jsx)(i.td,{children:"IZANAMI_OPENID_REDIRECT_URL"}),(0,r.jsx)(i.td,{children:"app.openid.redirect-url"}),(0,r.jsx)(i.td,{children:(0,r.jsx)(r.Fragment,{children:(0,r.jsx)(i.code,{children:"${app.exposition.url}/login"})})})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Scopes"}),(0,r.jsx)(i.td,{children:"IZANAMI_OPENID_SCOPES"}),(0,r.jsx)(i.td,{children:"app.openid.scopes"}),(0,r.jsx)(i.td,{children:(0,r.jsx)(r.Fragment,{children:(0,r.jsx)(i.code,{children:"openid email profile"})})})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Email field"}),(0,r.jsx)(i.td,{children:"IZANAMI_OPENID_EMAIL_FIELD"}),(0,r.jsx)(i.td,{children:"app.openid.email-field"}),(0,r.jsx)(i.td,{children:(0,r.jsx)(r.Fragment,{children:(0,r.jsx)(i.code,{children:"email"})})})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Username field"}),(0,r.jsx)(i.td,{children:"IZANAMI_OPENID_USERNAME_FIELD"}),(0,r.jsx)(i.td,{children:"app.openid.username-field"}),(0,r.jsx)(i.td,{children:(0,r.jsx)(r.Fragment,{children:(0,r.jsx)(i.code,{children:"name"})})})]})]})]}),"\n",(0,r.jsxs)(i.ul,{children:["\n",(0,r.jsxs)(i.li,{children:[(0,r.jsx)(i.code,{children:"Scopes"})," indicates which scopes should be requested when calling authorization url."]}),"\n",(0,r.jsxs)(i.li,{children:[(0,r.jsx)(i.code,{children:"Email field"})," indicates which field of id token should be used as user email"]}),"\n",(0,r.jsxs)(i.li,{children:[(0,r.jsx)(i.code,{children:"Username field"})," indicates which field of id token should be used as username"]}),"\n"]}),"\n",(0,r.jsx)(i.h3,{id:"wasmo",children:"Wasmo"}),"\n",(0,r.jsx)(i.p,{children:"If you chose to set up a WASMO instance alongside Izanami, you'll need to provide additional properties."}),"\n",(0,r.jsxs)(i.table,{children:[(0,r.jsx)(i.thead,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.th,{}),(0,r.jsx)(i.th,{children:"Environnement variable"}),(0,r.jsx)(i.th,{children:"Program argument"}),(0,r.jsx)(i.th,{children:"Default"})]})}),(0,r.jsxs)(i.tbody,{children:[(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Wasmo url"}),(0,r.jsx)(i.td,{children:"IZANAMI_WASMO_URL"}),(0,r.jsx)(i.td,{children:"app.wasmo.url"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Wasmo client id"}),(0,r.jsx)(i.td,{children:"IZANAMI_WASMO_CLIENT_ID"}),(0,r.jsx)(i.td,{children:"app.wasmo.client-id"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Wasmo client secret"}),(0,r.jsx)(i.td,{children:"IZANAMI_WASMO_CLIENT_SECRET"}),(0,r.jsx)(i.td,{children:"app.wasmo.client-secret"}),(0,r.jsx)(i.td,{})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Wasm cache TTL (ms)"}),(0,r.jsx)(i.td,{children:"IZANAMI_WASM_CACHE_TTL"}),(0,r.jsx)(i.td,{children:"app.wasm.cache.ttl"}),(0,r.jsx)(i.td,{children:"60000"})]})]})]}),"\n",(0,r.jsx)(i.h3,{id:"various-time-to-live",children:"Various time to live"}),"\n",(0,r.jsxs)(i.table,{children:[(0,r.jsx)(i.thead,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.th,{}),(0,r.jsx)(i.th,{children:"Environnement variable"}),(0,r.jsx)(i.th,{children:"Program argument"}),(0,r.jsx)(i.th,{children:"Default"})]})}),(0,r.jsxs)(i.tbody,{children:[(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Invitation time to live (s)"}),(0,r.jsx)(i.td,{children:"IZANAMI_INVITATIONS_TTL"}),(0,r.jsx)(i.td,{children:"app.invitations.ttl"}),(0,r.jsx)(i.td,{children:"86400 (24h)"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Session time to live (s)"}),(0,r.jsx)(i.td,{children:"IZANAMI_SESSIONS_TTL"}),(0,r.jsx)(i.td,{children:"app.sessions.ttl"}),(0,r.jsx)(i.td,{children:"3700"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"Password reset request time to live (s)"}),(0,r.jsx)(i.td,{children:"IZANAMI_PASSWORD_RESET_REQUEST_TTL"}),(0,r.jsx)(i.td,{children:"app.password-reset-requests.ttl"}),(0,r.jsx)(i.td,{children:"900 (15min)"})]})]})]}),"\n",(0,r.jsx)(i.h3,{id:"cors",children:"Cors"}),"\n",(0,r.jsxs)(i.p,{children:["Izanami uses ",(0,r.jsx)(i.a,{href:"https://www.playframework.com/documentation/3.0.x/CorsFilter#Configuring-the-CORS-filter",children:"Play CORS filter"})," to handle CORS."]}),"\n",(0,r.jsxs)(i.p,{children:["Therefore, CORS is configurable using ",(0,r.jsx)(i.code,{children:"play.filters.cors"})," configuration keys."]}),"\n",(0,r.jsx)(i.p,{children:"Alternatively, Izanami re-exposes these configuration keys with below env variables :"}),"\n",(0,r.jsxs)(i.table,{children:[(0,r.jsx)(i.thead,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.th,{children:"Play configuration key"}),(0,r.jsx)(i.th,{children:"Environnement variable"})]})}),(0,r.jsxs)(i.tbody,{children:[(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"play.filters.cors.pathPrefixes"}),(0,r.jsx)(i.td,{children:"IZANAMI_CORS_PATH_PREFIXES"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"play.filters.cors.allowedOrigins"}),(0,r.jsx)(i.td,{children:"IZANAMI_CORS_ALLOWED_ORIGINS"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"play.filters.cors.allowedHttpMethods"}),(0,r.jsx)(i.td,{children:"IZANAMI_CORS_ALLOWED_HTTP_METHODS"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"play.filters.cors.allowedHttpHeaders"}),(0,r.jsx)(i.td,{children:"IZANAMI_CORS_ALLOWED_HTTP_HEADERS"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"play.filters.cors.exposedHeaders"}),(0,r.jsx)(i.td,{children:"IZANAMI_CORS_EXPOSED_HEADERS"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"play.filters.cors.supportsCredentials"}),(0,r.jsx)(i.td,{children:"IZANAMI_CORS_SUPPORTS_CREDENTIALS"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"play.filters.cors.preflightMaxAge"}),(0,r.jsx)(i.td,{children:"IZANAMI_CORS_PREFLIGHT_MAX_AGE"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"play.filters.cors.serveForbiddenOrigins"}),(0,r.jsx)(i.td,{children:"IZANAMI_CORS_SERVE_FORBIDDEN_ORIGINS"})]})]})]}),"\n",(0,r.jsx)(i.h3,{id:"webhooks",children:"Webhooks"}),"\n",(0,r.jsx)(i.p,{children:"Webhooks use exponential backoff algorithm to space out retries in case of failures.\nIf retry exceed max allowed retry count, it stops trying and webhook won't be called."}),"\n",(0,r.jsx)(i.p,{children:"Duration is computed as :"}),"\n",(0,r.jsx)(i.p,{children:(0,r.jsx)(i.code,{children:"retryDelay = Math.min(initialDelay * Math.pow(multiplier, currentCount), maxDelay)"})}),"\n",(0,r.jsx)(i.p,{children:"With configurable values"}),"\n",(0,r.jsxs)(i.table,{children:[(0,r.jsx)(i.thead,{children:(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.th,{}),(0,r.jsx)(i.th,{children:"Play configuration key"}),(0,r.jsx)(i.th,{children:"Environnement variable"})]})}),(0,r.jsxs)(i.tbody,{children:[(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"max retry count"}),(0,r.jsx)(i.td,{children:"app.webhooks.retry.count"}),(0,r.jsx)(i.td,{children:"IZANAMI_WEBHOOK_RETRY_COUNT"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"initial delay (in seconds)"}),(0,r.jsx)(i.td,{children:"app.webhooks.retry.intial-delay"}),(0,r.jsx)(i.td,{children:"IZANAMI_WEBHOOK_RETRY_INITIAL_DELAY"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"max delay (in seconds)"}),(0,r.jsx)(i.td,{children:"app.webhooks.retry.max-delay"}),(0,r.jsx)(i.td,{children:"IZANAMI_WEBHOOK_RETRY_MAX_DELAY"})]}),(0,r.jsxs)(i.tr,{children:[(0,r.jsx)(i.td,{children:"multiplier"}),(0,r.jsx)(i.td,{children:"app.webhooks.retry.multiplier"}),(0,r.jsx)(i.td,{children:"IZANAMI_WEBHOOK_RETRY_MULTIPLIER"})]})]})]})]})}function o(e={}){const{wrapper:i}={...(0,n.a)(),...e.components};return i?(0,r.jsx)(i,{...e,children:(0,r.jsx)(a,{...e})}):a(e)}},1151:(e,i,s)=>{s.d(i,{Z:()=>l,a:()=>d});var r=s(7294);const n={},t=r.createContext(n);function d(e){const i=r.useContext(t);return r.useMemo((function(){return"function"==typeof e?e(i):{...i,...e}}),[i,e])}function l(e){let i;return i=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:d(e.components),r.createElement(t.Provider,{value:i},e.children)}}}]);