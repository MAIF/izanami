"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[4310],{9092:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>z,contentTitle:()=>w,default:()=>_,frontMatter:()=>k,metadata:()=>I,toc:()=>R});var r=t(5893),s=t(1151),i=t(1361);const a=t.p+"assets/images/empty-landing-page-ad5f11870c0c611ee6087d112bc28525.png",c=t.p+"assets/images/tenant-form-96ba29786896d2f1ff6ed34e5d02edbf.png",o=t.p+"assets/images/first-tenant-979ed958985511bdd1008fd550c528d5.png",l=t.p+"assets/images/project-form-4eb62048bbcc2b0a72fd91a090191c45.png",d=t.p+"assets/images/first-project-8a6d31af36b098ab322d81cd964ba521.png",u=t.p+"assets/images/feature-form-2fd3311d5162a7674bf73e1c8c35402e.png",p=t.p+"assets/images/first-feature-8ea5e4bebf007f564acb2e79c5fd64fc.png",h=t.p+"assets/images/test-menu-5ddc9590ed0c3b93ba8b4d8bff4a90d0.png",g=t.p+"assets/images/test-form-bf7db70d53d792474140d12d9c7892b5.png",f=t.p+"assets/images/feature-result-0fcb44ebc37d27f50e4f3431407b48c9.png",j=t.p+"assets/images/key-screen-451cc139a91b25aff22e1eaffcbe8d62.png",m=t.p+"assets/images/key-form-bec3afdd94723956b93cdc2d2e99b81a.png",x=t.p+"assets/images/key-secret-5ebc8340efcbcce0d2a5a1c35abd7071.png",y=t.p+"assets/images/url-screen-acc772559e5ad91cdcb1006c11c7b477.png";function b(e){const n={code:"code",pre:"pre",...(0,s.a)(),...e.components};return(0,r.jsx)(n.pre,{children:(0,r.jsx)(n.code,{className:"language-bash",children:'  curl -H "izanami-client-id: <YOUR CLIENT ID>" \\\n  -H "izanami-client-secret: <YOUR CLIENT SECRET>" \\\n  <FEATURE URL>\n'})})}function v(e={}){const{wrapper:n}={...(0,s.a)(),...e.components};return n?(0,r.jsx)(n,{...e,children:(0,r.jsx)(b,{...e})}):b(e)}const k={title:"Get started"},w="Get started",I={id:"getstarted/index",title:"Get started",description:"This guide will help you to:",source:"@site/docs/01-getstarted/index.mdx",sourceDirName:"01-getstarted",slug:"/getstarted/",permalink:"/izanami/docs/getstarted/",draft:!1,unlisted:!1,tags:[],version:"current",frontMatter:{title:"Get started"},sidebar:"tutorialSidebar",next:{title:"Feature flags",permalink:"/izanami/docs/concepts/"}},z={},R=[{value:"Instantiate Izanami locally",id:"instantiate-izanami-locally",level:2},{value:"Using docker",id:"using-docker",level:3},{value:"Using Java",id:"using-java",level:3},{value:"Your first feature",id:"your-first-feature",level:2},{value:"Creating tenant and project",id:"creating-tenant-and-project",level:3},{value:"Create your first feature",id:"create-your-first-feature",level:3},{value:"Test your feature locally",id:"test-your-feature-locally",level:3},{value:"Querying your feature",id:"querying-your-feature",level:2},{value:"Creating an API key",id:"creating-an-api-key",level:3},{value:"Retrieving request URL",id:"retrieving-request-url",level:3},{value:"Requesting using curl",id:"requesting-using-curl",level:3}];function T(e){const n={a:"a",code:"code",h1:"h1",h2:"h2",h3:"h3",li:"li",ol:"ol",p:"p",pre:"pre",ul:"ul",...(0,s.a)(),...e.components};return(0,r.jsxs)(r.Fragment,{children:[(0,r.jsx)(n.h1,{id:"get-started",children:"Get started"}),"\n",(0,r.jsx)(n.p,{children:"This guide will help you to:"}),"\n",(0,r.jsxs)(n.ol,{children:["\n",(0,r.jsx)(n.li,{children:"Get your first Izanami instance up and running"}),"\n",(0,r.jsx)(n.li,{children:"Creating your first feature"}),"\n",(0,r.jsx)(n.li,{children:"Requesting features through HTTP"}),"\n"]}),"\n",(0,r.jsxs)(n.p,{children:["To cover more advanced topics, either read about ",(0,r.jsx)(n.a,{href:"../concepts",children:"core concepts"})," or ",(0,r.jsx)(n.a,{href:"../guides/",children:"our guides"}),"."]}),"\n",(0,r.jsx)(n.h2,{id:"instantiate-izanami-locally",children:"Instantiate Izanami locally"}),"\n",(0,r.jsx)(n.p,{children:"There are several ways to start Izanami."}),"\n",(0,r.jsx)(n.h3,{id:"using-docker",children:"Using docker"}),"\n",(0,r.jsxs)(n.p,{children:["One solution si to run a postgres DB separately (change ",(0,r.jsx)(n.code,{children:"IZANAMI_PG_URI"})," to match your database setting)."]}),"\n",(0,r.jsx)(n.pre,{children:(0,r.jsx)(n.code,{className:"language-sh",children:"docker run --env IZANAMI_PG_URI=postgresql://postgres:postgres@host.docker.internal:5432/postgres -p 9000:9000 izanami\n"})}),"\n",(0,r.jsx)(n.p,{children:"Alternatively, you can run following docker-compose file"}),"\n",(0,r.jsx)(n.pre,{children:(0,r.jsx)(n.code,{className:"language-yml",children:'# Use postgres/example user/password credentials\nversion: "3.1"\n\nservices:\n  db:\n    image: postgres\n    restart: always\n    environment:\n      POSTGRES_USER: postgres\n      POSTGRES_PASSWORD: postgres\n    ports:\n      - 5432:5432\n  izanami:\n    image: maif/izanami:2.4.0\n    environment:\n      IZANAMI_PG_URI: postgresql://postgres:postgres@host.docker.internal:5432/postgres\n    ports:\n      - 9000:9000\n'})}),"\n",(0,r.jsx)(n.h3,{id:"using-java",children:"Using Java"}),"\n",(0,r.jsxs)(n.p,{children:["You'll need to run a postgres database to start Izanami. Replace ",(0,r.jsx)(n.code,{children:"app.pg.uri"})," with your database values."]}),"\n",(0,r.jsx)(n.pre,{children:(0,r.jsx)(n.code,{className:"language-sh",children:"java -jar \\\n  -Dapp.pg.uri=postgresql://postgres:postgres@localhost:5432/postgres \\\n  izanami.jar\n"})}),"\n",(0,r.jsx)(n.h2,{id:"your-first-feature",children:"Your first feature"}),"\n",(0,r.jsx)(n.h3,{id:"creating-tenant-and-project",children:"Creating tenant and project"}),"\n",(0,r.jsx)(n.p,{children:"Before creating a feature, you need to create a tenant and a project."}),"\n",(0,r.jsx)(n.p,{children:"These two organizations levels will help you to keep thing organized"}),"\n",(0,r.jsx)(n.p,{children:"When login in for the first time, Izanami will ask you to create a tenant."}),"\n",(0,r.jsx)("img",{src:a}),"\n",(0,r.jsx)(n.p,{children:"When clicking this button, Izanami will display tenant creation form, tenant name can contain:"}),"\n",(0,r.jsxs)(n.ul,{children:["\n",(0,r.jsx)(n.li,{children:"lowercase letters"}),"\n",(0,r.jsx)(n.li,{children:"numbers"}),"\n",(0,r.jsxs)(n.li,{children:[(0,r.jsx)(n.code,{children:"-"})," and ",(0,r.jsx)(n.code,{children:"_"})," characters"]}),"\n"]}),"\n",(0,r.jsx)("img",{src:c}),"\n",(0,r.jsx)(n.p,{children:"Once tenant is created, Izanami redirects you its page, and indicates that there is no project for this tenant yet."}),"\n",(0,r.jsx)("img",{src:o}),"\n",(0,r.jsxs)(n.p,{children:["On this screen there are two choices, we can either create a new project from scratch or import data, for this guide we will create a new project.\nIf you're interested in importing data, check the ",(0,r.jsx)(n.a,{href:"../guides/import-from-v1",children:"data importing guide"}),"."]}),"\n",(0,r.jsx)(n.p,{children:"After clicking the creation button, a form is displayed. Project name can contain following symbols :"}),"\n",(0,r.jsxs)(n.ul,{children:["\n",(0,r.jsx)(n.li,{children:"letters (uppercase or lowercase)"}),"\n",(0,r.jsx)(n.li,{children:"numbers"}),"\n",(0,r.jsxs)(n.li,{children:[(0,r.jsx)(n.code,{children:"-"})," and ",(0,r.jsx)(n.code,{children:"_"})," characters"]}),"\n"]}),"\n",(0,r.jsx)("img",{src:l}),"\n",(0,r.jsx)(n.p,{children:"After validating this form, Izanami once again redirects you, this time on the new project page."}),"\n",(0,r.jsx)("img",{src:d}),"\n",(0,r.jsx)(n.h3,{id:"create-your-first-feature",children:"Create your first feature"}),"\n",(0,r.jsx)(n.p,{children:'Now that we have a project, let\'s create our first feature. Just click on the "Create new feature" button.'}),"\n",(0,r.jsx)(n.p,{children:"In this form, feature name can contain following symbols:"}),"\n",(0,r.jsxs)(n.ul,{children:["\n",(0,r.jsx)(n.li,{children:"letters (uppercase or lowercase)"}),"\n",(0,r.jsx)(n.li,{children:"numbers"}),"\n",(0,r.jsxs)(n.li,{children:[(0,r.jsx)(n.code,{children:"-"}),", ",(0,r.jsx)(n.code,{children:"_"})," and ",(0,r.jsx)(n.code,{children:":"})," characters"]}),"\n"]}),"\n",(0,r.jsx)(n.p,{children:"For now, we'll keep feature as simple as possible: feature will be either active or inactive for everyone."}),"\n",(0,r.jsx)(n.p,{children:'Check "Enabled" checkbox if you want your feature to be active, let it unchecked otherwise.'}),"\n",(0,r.jsx)("img",{src:u}),"\n",(0,r.jsx)(n.p,{children:"After saving this new feature, our project doesn't look that empty anymore."}),"\n",(0,r.jsx)("img",{src:p}),"\n",(0,r.jsx)(n.h3,{id:"test-your-feature-locally",children:"Test your feature locally"}),"\n",(0,r.jsx)(n.p,{children:"Izanami allows to test feature from backoffice. Click on action icon at the end of your feature row."}),"\n",(0,r.jsx)("img",{src:h}),"\n",(0,r.jsx)(n.p,{children:'Now click on "Test Feature" item to display test form.'}),"\n",(0,r.jsx)("img",{src:g}),"\n",(0,r.jsx)(n.p,{children:"This form allows to specify several query parameters, but we don't need it right now, just click on the test button."}),"\n",(0,r.jsx)(n.p,{children:"A result textbox will appear indicating if feature is currently active or not."}),"\n",(0,r.jsx)("img",{src:f}),"\n",(0,r.jsx)(n.h2,{id:"querying-your-feature",children:"Querying your feature"}),"\n",(0,r.jsx)(n.p,{children:"To query feature state from external application, we'll need to create a client key."}),"\n",(0,r.jsx)(i.j,{children:"We recommand to create one key by client application"}),"\n",(0,r.jsx)(n.h3,{id:"creating-an-api-key",children:"Creating an API key"}),"\n",(0,r.jsx)(n.p,{children:'First let\'s go to the API key screen, click on "Keys" entry of left menu.'}),"\n",(0,r.jsx)("img",{src:j}),"\n",(0,r.jsx)(n.p,{children:'Click on "Create new key" to display key creation form. Key name can contain following symbols:'}),"\n",(0,r.jsxs)(n.ul,{children:["\n",(0,r.jsx)(n.li,{children:"letters (uppercase or lowercase)"}),"\n",(0,r.jsx)(n.li,{children:"numbers"}),"\n",(0,r.jsxs)(n.li,{children:[(0,r.jsx)(n.code,{children:"-"})," and ",(0,r.jsx)(n.code,{children:"_"})," characters"]}),"\n"]}),"\n",(0,r.jsx)(n.p,{children:'You\'ll also need to select allowed projects in select. Alternatively, you can check "admin" checkbox.'}),"\n",(0,r.jsx)(i.j,{children:"Admin keys have right on all tenant projects"}),"\n",(0,r.jsx)("img",{src:m}),"\n",(0,r.jsx)(n.p,{children:"Once your key is created, a popup will display associated client id and secret."}),"\n",(0,r.jsx)("img",{src:x}),"\n",(0,r.jsx)(n.h3,{id:"retrieving-request-url",children:"Retrieving request URL"}),"\n",(0,r.jsx)(n.p,{children:'To retrieve feature call URL, you\'ll need to get back to your project.\nTo do this click on "Projects" item on left menu, and then click on your project.'}),"\n",(0,r.jsx)(n.p,{children:"Once you are back on your project screen, click on the link icon on your feature row."}),"\n",(0,r.jsx)("img",{src:y}),"\n",(0,r.jsx)(n.p,{children:'Click the "copy" button to copy feature URL.'}),"\n",(0,r.jsx)(i.j,{children:(0,r.jsx)(n.p,{children:"Izanami is using technical ID in URL. The reason it's not using feature name\nis to allow changing feature names without breaking client calls"})}),"\n",(0,r.jsx)(n.h3,{id:"requesting-using-curl",children:"Requesting using curl"}),"\n",(0,r.jsx)(n.p,{children:"You've got everything you need to query your feature from curl."}),"\n",(0,r.jsx)(v,{})]})}function _(e={}){const{wrapper:n}={...(0,s.a)(),...e.components};return n?(0,r.jsx)(n,{...e,children:(0,r.jsx)(T,{...e})}):T(e)}},1361:(e,n,t)=>{t.d(n,{j:()=>a});const r={description__trivia:"description__trivia_yesz"};var s=t(4625),i=t(5893);function a(e){let{children:n}=e;return(0,i.jsxs)("div",{className:r.description__trivia,children:[(0,i.jsx)("img",{src:s.Z}),(0,i.jsx)("div",{children:n})]})}},4625:(e,n,t)=>{t.d(n,{Z:()=>r});const r=t.p+"assets/images/izanami_compressed-659337582c1667d92966e42fa1bfea3f.webp"},1151:(e,n,t)=>{t.d(n,{Z:()=>c,a:()=>a});var r=t(7294);const s={},i=r.createContext(s);function a(e){const n=r.useContext(i);return r.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function c(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(s):e.components||s:a(e.components),r.createElement(i.Provider,{value:n},e.children)}}}]);