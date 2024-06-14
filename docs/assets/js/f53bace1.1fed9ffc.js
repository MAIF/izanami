"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[5960],{7682:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>r,contentTitle:()=>o,default:()=>u,frontMatter:()=>s,metadata:()=>c,toc:()=>l});var i=t(5893),a=t(1151);const s={title:"Clients"},o=void 0,c={id:"clients/index",title:"Clients",description:"For now, Izanami only supports Java client.",source:"@site/docs/05-clients/index.mdx",sourceDirName:"05-clients",slug:"/clients/",permalink:"/izanami/docs/clients/",draft:!1,unlisted:!1,tags:[],version:"current",frontMatter:{title:"Clients"},sidebar:"tutorialSidebar",previous:{title:"Webhooks",permalink:"/izanami/docs/guides/webhooks"},next:{title:"Java client",permalink:"/izanami/docs/clients/java/"}},r={},l=[{value:"Why do I need a client ?",id:"why-do-i-need-a-client-",level:2}];function d(e){const n={a:"a",h2:"h2",li:"li",p:"p",strong:"strong",ul:"ul",...(0,a.a)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsxs)(n.p,{children:["For now, Izanami only supports ",(0,i.jsx)(n.a,{href:"/izanami/docs/clients/java/",children:"Java client"}),"."]}),"\n",(0,i.jsx)(n.p,{children:"We plan to add support for Javascript (node / in browser client)."}),"\n",(0,i.jsxs)(n.p,{children:["If you need another client, don't hesitate to ",(0,i.jsx)("a",{href:"https://github.com/MAIF/izanami/issues",children:"open an issue"})," or come discuss it ",(0,i.jsx)("a",{href:"TODO",children:"on discord"}),", we would be happy to help you with it."]}),"\n",(0,i.jsx)(n.h2,{id:"why-do-i-need-a-client-",children:"Why do I need a client ?"}),"\n",(0,i.jsx)(n.p,{children:"It's perfectly correct to call Izanami directly through HTTP. However, a specific client can take care of some boring stuff for you."}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.strong,{children:"Cache"})," : caching feature state can be useful to avoid making too many requests."]}),"\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.strong,{children:"Local evaluation"})," : instead of caching feature activation status at a given time, clients can cache feature activation strategy to recompute activation status locally."]}),"\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.strong,{children:"Resilience"})," : client can offer several resilience mechanisms to increase your application resilience, such as fallback on previous state or default value."]}),"\n"]})]})}function u(e={}){const{wrapper:n}={...(0,a.a)(),...e.components};return n?(0,i.jsx)(n,{...e,children:(0,i.jsx)(d,{...e})}):d(e)}},1151:(e,n,t)=>{t.d(n,{Z:()=>c,a:()=>o});var i=t(7294);const a={},s=i.createContext(a);function o(e){const n=i.useContext(s);return i.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function c(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:o(e.components),i.createElement(s.Provider,{value:n},e.children)}}}]);