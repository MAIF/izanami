"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[1046],{8402:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>l,contentTitle:()=>o,default:()=>h,frontMatter:()=>r,metadata:()=>s,toc:()=>d});var i=t(5893),a=t(1151),c=t(1361);const r={title:"Cache"},o=void 0,s={id:"clients/java/cache",title:"Cache",description:"Client put features in cache with their activation conditions.",source:"@site/docs/05-clients/01-java/01-cache.mdx",sourceDirName:"05-clients/01-java",slug:"/clients/java/cache",permalink:"/izanami/docs/clients/java/cache",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:1,frontMatter:{title:"Cache"},sidebar:"tutorialSidebar",previous:{title:"Java client",permalink:"/izanami/docs/clients/java/"},next:{title:"Error handling",permalink:"/izanami/docs/clients/java/error-handling"}},l={},d=[];function u(e){const n={code:"code",p:"p",pre:"pre",strong:"strong",...(0,a.a)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(n.p,{children:"Client put features in cache with their activation conditions."}),"\n",(0,i.jsxs)(n.p,{children:["This means that (except for script features) client can ",(0,i.jsx)(n.strong,{children:"compute feature activation locally"}),", without the need to perform an http call to remote Izanami.\nIn this mode, feature activation conditions are ",(0,i.jsx)(n.strong,{children:"updated periodically in background"}),"."]}),"\n",(0,i.jsx)(c.j,{children:(0,i.jsx)(n.p,{children:"Script features can't be evaluated locally, since client does not embed a WASM\nruntime."})}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-java",children:"IzanamiClient client = IzanamiClient.newBuilder(\n    connectionInformation()\n      .withUrl(<REMOTE_IZANAMI_BASE_URL>/api)\n      .withClientId(<YOUR_KEY_CLIENT_ID>)\n      .withClientSecret(<YOUR_KEY_CLIENT_SECRET>)\n  ).withCacheConfiguration(\n    FeatureCacheConfiguration\n      .newBuilder()\n      .enabled(true) // Enable caching feature activation conditions and local activation computation\n      .build()\n  ).build();\n"})}),"\n",(0,i.jsx)(n.p,{children:"By default, cache is disabled and a query is sent to remote Izanami for each request."}),"\n",(0,i.jsx)(n.p,{children:"When cache is active, it's always possible to skip it for some queries :"}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-java",children:"CompletableFuture<Boolean> result = client.checkFeatureActivation(\n    newSingleFeatureRequest(id)\n    .ignoreCache(true) // Ignore cache and perform request for this query\n);\n\nCompletableFuture<Boolean> another = client.checkFeatureActivations(\n  newFeatureRequest()\n    .withFeatures(<FEATURE_IDS>)\n    .ignoreCache(true) // Ignore cache and perform request for this query\n);\n"})}),"\n",(0,i.jsx)(c.j,{children:(0,i.jsx)(n.p,{children:"Ignoring cache this way will still update cache with retrieved activation\nconditions."})})]})}function h(e={}){const{wrapper:n}={...(0,a.a)(),...e.components};return n?(0,i.jsx)(n,{...e,children:(0,i.jsx)(u,{...e})}):u(e)}},1361:(e,n,t)=>{t.d(n,{j:()=>r});const i={description__trivia:"description__trivia_yesz"};var a=t(6678),c=t(5893);function r(e){let{children:n}=e;return(0,c.jsxs)("div",{className:i.description__trivia,children:[(0,c.jsx)("img",{src:a.Z}),(0,c.jsx)("div",{children:n})]})}},6678:(e,n,t)=>{t.d(n,{Z:()=>i});const i=t.p+"assets/images/izanami-fcff3cbcd789d673683f3365a3ddf9e4.png"},1151:(e,n,t)=>{t.d(n,{Z:()=>o,a:()=>r});var i=t(7294);const a={},c=i.createContext(a);function r(e){const n=i.useContext(c);return i.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function o(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:r(e.components),i.createElement(c.Provider,{value:n},e.children)}}}]);