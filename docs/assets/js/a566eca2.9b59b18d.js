"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[4955],{633:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>m,contentTitle:()=>h,default:()=>b,frontMatter:()=>u,metadata:()=>f,toc:()=>p});var a=t(5893),s=t(1151),o=t(1361);const i=t.p+"assets/images/result-type-menu-e9ef4f190c8335193d83d27192f3e3a7.png",r=t.p+"assets/images/alternative-value-3bf84d4023abff3dd702e3a4cf470163.png",l=t.p+"assets/images/feature-form-15108a6bd16f65bf5a03f7f7df93d2e6.png",c=(t.p,t.p+"assets/images/overload-in-project-1f772fef3ad09927e4f5c5186dd485c8.png"),d=t.p+"assets/images/string-feature-in-project-86a20b5df6692780cd5ca41ef5777602.png",u={title:"Non boolean features"},h="Why use non boolean features",f={id:"guides/non-boolean-features",title:"Non boolean features",description:"Boolean features (features that can only respond true or false)",source:"@site/docs/04-guides/15-non-boolean-features.mdx",sourceDirName:"04-guides",slug:"/guides/non-boolean-features",permalink:"/izanami/docs/guides/non-boolean-features",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:15,frontMatter:{title:"Non boolean features"},sidebar:"tutorialSidebar",previous:{title:"Data transfer",permalink:"/izanami/docs/guides/transfer-data"},next:{title:"Access tokens",permalink:"/izanami/docs/guides/personnal-access-tokens"}},m={},p=[{value:"Preventing impossible states",id:"preventing-impossible-states",level:2},{value:"Configuration",id:"configuration",level:2},{value:"Make your app more flexible by moving some logic to Izanami",id:"make-your-app-more-flexible-by-moving-some-logic-to-izanami",level:2},{value:"Non boolean feature creation",id:"non-boolean-feature-creation",level:2},{value:"Non boolean feature overload",id:"non-boolean-feature-overload",level:2}];function v(e){const n={code:"code",h1:"h1",h2:"h2",li:"li",p:"p",strong:"strong",ul:"ul",...(0,s.a)(),...e.components};return(0,a.jsxs)(a.Fragment,{children:[(0,a.jsx)(n.p,{children:"Boolean features (features that can only respond true or false)\nare great for many cases, but sometimes it's not enough."}),"\n",(0,a.jsx)(o.j,{children:"String and number features are available from Izanami 2.6.0."}),"\n",(0,a.jsx)(n.h1,{id:"why-use-non-boolean-features",children:"Why use non boolean features"}),"\n",(0,a.jsx)(n.h2,{id:"preventing-impossible-states",children:"Preventing impossible states"}),"\n",(0,a.jsx)(n.p,{children:"Let's say you want to add a comment feature on your e-commerce website."}),"\n",(0,a.jsx)(n.p,{children:"This feature should allow users to leave comments on products.\nWe would also like to allow users to rate other comments to upvote the most usefull ones."}),"\n",(0,a.jsxs)(n.p,{children:["A simple approach would be to define two features: ",(0,a.jsx)(n.code,{children:"comments"})," and ",(0,a.jsx)(n.code,{children:"comments-vote"}),"."]}),"\n",(0,a.jsx)(n.p,{children:'However defining these as two separate features introduces what we could call an "impossible state".'}),"\n",(0,a.jsxs)(n.p,{children:["Indeed, it doesn't make sense to activate ",(0,a.jsx)(n.code,{children:"comments-vote"})," feature while ",(0,a.jsx)(n.code,{children:"comments"})," is disabled,\nsince users can't vote on comments that doesn't exist.\nWith some luck, client application handles such cases correctly, but it can also lead to bugs\nif such state haven't been anticipated."]}),"\n",(0,a.jsx)(n.p,{children:'As we can see, multiplying boolean features for concomitant features can leads to a\ncombinatorial explosion, with some cases leading to "impossible states".'}),"\n",(0,a.jsx)(n.p,{children:"In such cases, it's always a good idea to make impossible states unrepresentable."}),"\n",(0,a.jsx)(n.p,{children:(0,a.jsx)(n.strong,{children:"To prevent impossible states, you could use multi valued string or number features."})}),"\n",(0,a.jsxs)(n.p,{children:["For instance, you could define a single ",(0,a.jsx)(n.code,{children:"comments"})," string feature, that could take several values:"]}),"\n",(0,a.jsxs)(n.ul,{children:["\n",(0,a.jsxs)(n.li,{children:[(0,a.jsx)(n.code,{children:'"disabled"'}),": both comments and votes are disabled"]}),"\n",(0,a.jsxs)(n.li,{children:[(0,a.jsx)(n.code,{children:'"comments"'}),": comment are enabled"]}),"\n",(0,a.jsxs)(n.li,{children:[(0,a.jsx)(n.code,{children:'"votes"'}),": comments and votes are enabled"]}),"\n"]}),"\n",(0,a.jsx)(n.p,{children:"This make things both simpler in Izanami and in your client code."}),"\n",(0,a.jsx)(n.h2,{id:"configuration",children:"Configuration"}),"\n",(0,a.jsx)(n.p,{children:"String features can also be used to store configuration values."}),"\n",(0,a.jsx)(n.p,{children:"This may prove usefull if you've got some configuration to change at\nruntime or if you don't have any dedicated configuration server."}),"\n",(0,a.jsx)(n.p,{children:"However, it's not a good idea to store sensitive (such as database passwords) data in Izanami,\nsince it does not have any secret handling mechanisms for feature values."}),"\n",(0,a.jsx)(n.h2,{id:"make-your-app-more-flexible-by-moving-some-logic-to-izanami",children:"Make your app more flexible by moving some logic to Izanami"}),"\n",(0,a.jsx)(n.p,{children:"Sometimes you can't predict everything you want to to with a new feature,\nand it may be usefull to allow updating application code without having ton redeploy it."}),"\n",(0,a.jsx)(n.p,{children:'For such cases, you can leave yourself "escape hatches", for instance by creating a string feature\nthat contains a css stylesheet.'}),"\n",(0,a.jsx)(n.p,{children:"This way, you can update your application look and feel without having to redeploy it."}),"\n",(0,a.jsx)(o.j,{children:(0,a.jsx)(n.p,{children:"Be carefull with this kind of flag, as it introduces code that is not present\nduring build, code analysis, automated tests, ..."})}),"\n",(0,a.jsx)(n.h1,{id:"using-non-boolean-features-with-izanami",children:"Using non boolean features with Izanami"}),"\n",(0,a.jsx)(n.h2,{id:"non-boolean-feature-creation",children:"Non boolean feature creation"}),"\n",(0,a.jsx)(n.p,{children:"Define non boolean feature is a lot like defining boolean features, it's basically\njust a select value to change at feature creation."}),"\n",(0,a.jsx)("img",{src:i}),"\n",(0,a.jsx)(n.p,{children:'Instead of leaving "Result type" field to "boolean" when creating a feature, you can set it to "string" or "number".'}),"\n",(0,a.jsx)(n.p,{children:'You\'ll have to indicate the "Base value" for your feature, this value will be used as feature\nvalue is feature is enabled and if there is no active alternative value / overload.'}),"\n",(0,a.jsx)("img",{src:l}),"\n",(0,a.jsx)(n.p,{children:"You can then define alternative values, that will be used if their conditions are satisfied."}),"\n",(0,a.jsx)("img",{src:r}),"\n",(0,a.jsxs)(o.j,{children:[(0,a.jsx)(n.p,{children:"For boolean features, activation conditions order is not important: one\nmatching condition is enough to make the feature active."}),(0,a.jsx)("br",{}),(0,a.jsxs)(n.p,{children:["For string an number features,"," ","\n",(0,a.jsx)("strong",{children:"order of alternative values matters"}),": feature value will be\nthe first active alternative value."]})]}),"\n",(0,a.jsxs)(n.p,{children:["In project view, Izanami displays an icon next to the feature name to indicate\nits type (here it displays an ",(0,a.jsx)(n.code,{children:'"a"'})," indicating a string feature)."]}),"\n",(0,a.jsx)("img",{src:d}),"\n",(0,a.jsx)(n.h2,{id:"non-boolean-feature-overload",children:"Non boolean feature overload"}),"\n",(0,a.jsxs)(n.p,{children:["Just like boolean features, you can overload non boolean features in a project.\nThere is a very important rule for overload, that stands for both boolean and non boolean features:\n",(0,a.jsx)(n.strong,{children:"an overload must have the same result type that the base feature"}),"."]}),"\n",(0,a.jsx)(n.p,{children:"This ensure that client code always handle feature result with the same logic, independently of the context."}),"\n",(0,a.jsx)("img",{src:c})]})}function b(e={}){const{wrapper:n}={...(0,s.a)(),...e.components};return n?(0,a.jsx)(n,{...e,children:(0,a.jsx)(v,{...e})}):v(e)}},1361:(e,n,t)=>{t.d(n,{j:()=>i});const a={description__trivia:"description__trivia_yesz"};var s=t(4625),o=t(5893);function i(e){let{children:n}=e;return(0,o.jsxs)("div",{className:a.description__trivia,children:[(0,o.jsx)("img",{src:s.Z}),(0,o.jsx)("div",{children:n})]})}},4625:(e,n,t)=>{t.d(n,{Z:()=>a});const a=t.p+"assets/images/izanami_compressed-659337582c1667d92966e42fa1bfea3f.webp"},1151:(e,n,t)=>{t.d(n,{Z:()=>r,a:()=>i});var a=t(7294);const s={},o=a.createContext(s);function i(e){const n=a.useContext(o);return a.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function r(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(s):e.components||s:i(e.components),a.createElement(o.Provider,{value:n},e.children)}}}]);