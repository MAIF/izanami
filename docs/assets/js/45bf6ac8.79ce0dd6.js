"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[562],{3473:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>f,contentTitle:()=>p,default:()=>g,frontMatter:()=>h,metadata:()=>m,toc:()=>x});var i=t(5893),s=t(1151),a=t(1361),r=t(4866),o=t(5162);const l=t.p+"assets/images/filled-feature-form-bfd2439294738506d401eed3e5241a02.png",c=t.p+"assets/images/active-mobile-2f75f96d7fd73a05a410672bdf34eec1.png",u=t.p+"assets/images/active-prod-mobile-da4db705d14906fbf21cdd46216d1c12.png",d=t.p+"assets/images/inactive-prod-9d136c34035fcc1ce2b006fdaec45d73.png",h={title:"Local script features"},p=void 0,m={id:"guides/local-scripts",title:"Local script features",description:"Embedding local script features in Izanami allows to leverage the power and flexibility of script features without needing to deploy a WASMO instance in production.\\_createMdxContent",source:"@site/docs/04-guides/06-local-scripts.mdx",sourceDirName:"04-guides",slug:"/guides/local-scripts",permalink:"/izanami/docs/guides/local-scripts",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:6,frontMatter:{title:"Local script features"},sidebar:"tutorialSidebar",previous:{title:"Migrate from V1",permalink:"/izanami/docs/guides/import-from-v1"},next:{title:"Remote script features",permalink:"/izanami/docs/guides/remote-script"}},f={},x=[{value:"Creating your script with WASMO cli",id:"creating-your-script-with-wasmo-cli",level:2}];function b(e){const n={a:"a",code:"code",h2:"h2",li:"li",ol:"ol",p:"p",pre:"pre",ul:"ul",...(0,s.a)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(n.p,{children:"Embedding local script features in Izanami allows to leverage the power and flexibility of script features without needing to deploy a WASMO instance in production._createMdxContent"}),"\n",(0,i.jsxs)(n.p,{children:["This strategy implies that you'll be on your own to handle plugins versions and lifecycle, though ",(0,i.jsx)(n.a,{href:"https://github.com/MAIF/wasmo/tree/main/cli",children:"wasmo-CLI"})," could help with this."]}),"\n",(0,i.jsx)(a.j,{children:(0,i.jsxs)(n.p,{children:["Since this method requires embedding Base64 encoded WASM scripts in Izanami\ndatabase, it's recommended to use languages that produce lightweight WASM\nfiles, such as ",(0,i.jsx)(n.code,{children:"go"})," or ",(0,i.jsx)(n.code,{children:"OPA"}),"."]})}),"\n",(0,i.jsx)(n.h2,{id:"creating-your-script-with-wasmo-cli",children:"Creating your script with WASMO cli"}),"\n",(0,i.jsx)(n.p,{children:'In this example, we\'ll create a script feature that returns true if request context contains "mobile".'}),"\n",(0,i.jsx)(n.p,{children:"We want this feature to be active for following contexts :"}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsx)(n.li,{children:(0,i.jsx)(n.code,{children:"prod/mobile"})}),"\n",(0,i.jsx)(n.li,{children:(0,i.jsx)(n.code,{children:"mobile"})}),"\n",(0,i.jsx)(n.li,{children:(0,i.jsx)(n.code,{children:"dev/mobile"})}),"\n",(0,i.jsx)(n.li,{children:(0,i.jsx)(n.code,{children:"mobile"})}),"\n",(0,i.jsx)(n.li,{children:"..."}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"and to be inactive for following contexts :"}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsx)(n.li,{children:(0,i.jsx)(n.code,{children:"prod"})}),"\n",(0,i.jsx)(n.li,{children:(0,i.jsx)(n.code,{children:"dev"})}),"\n",(0,i.jsx)(n.li,{children:"empty context"}),"\n",(0,i.jsx)(n.li,{children:"..."}),"\n"]}),"\n",(0,i.jsxs)(n.p,{children:["First you'll need to ",(0,i.jsx)(n.a,{href:"https://github.com/MAIF/wasmo/tree/main/cli#installation",children:"install WASMO CLI"}),"."]}),"\n",(0,i.jsxs)(n.p,{children:["Then instantiate a new project.\nWasmo provide some built-in templates, we'll be using the ",(0,i.jsx)(n.code,{children:"Izanami opa"})," one for this example.\nYou'll need to open a command shell for this."]}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"wasmo init --name=izanami-mobile --template=izanami_opa\n"})}),"\n",(0,i.jsx)(a.j,{children:(0,i.jsxs)(n.p,{children:["If you want to use go instead of opa for this tutorial, simply replace\n",(0,i.jsx)(n.code,{children:"izanami_opa"})," with ",(0,i.jsx)(n.code,{children:"izanami_go"}),"."]})}),"\n",(0,i.jsxs)(n.p,{children:["This command should create a ",(0,i.jsx)(n.code,{children:"izanami-mobile"})," directory where you run it.\nThis directory should contain two files : ",(0,i.jsx)(n.code,{children:"main.go"})," and ",(0,i.jsx)(n.code,{children:"go.mod"}),"."]}),"\n",(0,i.jsxs)(n.p,{children:["For our feature, we'll only need to modify ",(0,i.jsx)(n.code,{children:"main.go"})," with the following code:"]}),"\n",(0,i.jsxs)(r.Z,{defaultValue:"opa",values:[{label:"Open Policy Agent (OPA)",value:"opa"},{label:"Go",value:"go"}],children:[(0,i.jsx)(o.Z,{value:"opa",children:(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-rego",children:'package example\nimport rego.v1\n\ndefault can_access = false\n\ncan_access if {\n  some x in input.executionContext\n  x == "mobile"\n}\n'})})}),(0,i.jsx)(o.Z,{value:"go",children:(0,i.jsxs)("div",{children:[(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-go",children:'package main\n\nimport (\n  "github.com/extism/go-pdk"\n  "slices"\n)\n\ntype Input struct {\n  ExecutionContext []string `json:"executionContext"`\n}\n\ntype Output struct {\n  Active bool `json:"active"`\n}\n\n//export execute\nfunc execute() int32 {\n  input := Input{}\n  _ = pdk.InputJSON(&input)\n\n  active := slices.Contains(input.ExecutionContext, "mobile")\n\n  output := Output{active}\n  _ = pdk.OutputJSON(&output)\n  return 0\n}\n\nfunc main() {}\n'})}),(0,i.jsx)(n.p,{children:"This code may seem obscure at first, however it does a quite basic task :"}),(0,i.jsxs)(n.ol,{children:["\n",(0,i.jsxs)(n.li,{children:["It reads ",(0,i.jsx)(n.code,{children:"executionContext"})," fields provided by Izanami, that indicates the context that was used for querying Izanami."]}),"\n",(0,i.jsxs)(n.li,{children:["It checks if ",(0,i.jsx)(n.code,{children:"mobile"})," contexts is present somewhere in context path"]}),"\n",(0,i.jsxs)(n.li,{children:["It writes the result within the ",(0,i.jsx)(n.code,{children:"active"})," field of the output"]}),"\n"]})]})})]}),"\n",(0,i.jsx)(n.p,{children:"As you can see, Open Policy Agent is arguably best suited for this kind of script."}),"\n",(0,i.jsx)(n.p,{children:"Once you're done editing the file, build your script with the following command :"}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"wasmo build --host=OneShotDocker --path=izanami-mobile\n"})}),"\n",(0,i.jsxs)(n.p,{children:["This will generate an ",(0,i.jsx)(n.code,{children:"izanami-mobile-1.0.0.wasm"})," file at the root of your directory."]}),"\n",(0,i.jsx)(n.p,{children:"You can now convert this file to base64, for instance, with the following command :"}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"openssl base64 -A -in ./izanami-mobile/izanami-mobile-1.0.0.wasm -out out.txt\n"})}),"\n",(0,i.jsx)(a.j,{children:(0,i.jsxs)(n.p,{children:["Izanami requires base64 to be on a single line, that's why we use the ",(0,i.jsx)(n.code,{children:"-A"}),"\nflag in the above command."]})}),"\n",(0,i.jsx)(n.p,{children:"Once you've got your Base64 script, you'll need to create a WASM Base64 feature in Izanami."}),"\n",(0,i.jsx)(n.p,{children:'To do this, select "New WASM script" feature type, then indicate that WASM script is of type "Base64".\nThen you\'ll need to paste your base64 WASM script in the appropriate input.'}),"\n",(0,i.jsx)("img",{src:l}),"\n",(0,i.jsxs)(n.p,{children:["Once your feature is created, we can test it.\nLet's first see if it works with a basic ",(0,i.jsx)(n.code,{children:"mobile"})," context."]}),"\n",(0,i.jsx)("img",{src:c}),"\n",(0,i.jsxs)(n.p,{children:["Our script also works with subcontext containing a ",(0,i.jsx)(n.code,{children:"mobile"})," subcontext."]}),"\n",(0,i.jsx)("img",{src:u}),"\n",(0,i.jsxs)(n.p,{children:["As expected, it does not activate feature for a context that does not contain ",(0,i.jsx)(n.code,{children:"mobile"}),"."]}),"\n",(0,i.jsx)("img",{src:d})]})}function g(e={}){const{wrapper:n}={...(0,s.a)(),...e.components};return n?(0,i.jsx)(n,{...e,children:(0,i.jsx)(b,{...e})}):b(e)}},5162:(e,n,t)=>{t.d(n,{Z:()=>r});t(7294);var i=t(6905);const s={tabItem:"tabItem_Ymn6"};var a=t(5893);function r(e){let{children:n,hidden:t,className:r}=e;return(0,a.jsx)("div",{role:"tabpanel",className:(0,i.Z)(s.tabItem,r),hidden:t,children:n})}},4866:(e,n,t)=>{t.d(n,{Z:()=>y});var i=t(7294),s=t(6905),a=t(2466),r=t(6550),o=t(469),l=t(1980),c=t(7392),u=t(12);function d(e){return i.Children.toArray(e).filter((e=>"\n"!==e)).map((e=>{if(!e||(0,i.isValidElement)(e)&&function(e){const{props:n}=e;return!!n&&"object"==typeof n&&"value"in n}(e))return e;throw new Error(`Docusaurus error: Bad <Tabs> child <${"string"==typeof e.type?e.type:e.type.name}>: all children of the <Tabs> component should be <TabItem>, and every <TabItem> should have a unique "value" prop.`)}))?.filter(Boolean)??[]}function h(e){const{values:n,children:t}=e;return(0,i.useMemo)((()=>{const e=n??function(e){return d(e).map((e=>{let{props:{value:n,label:t,attributes:i,default:s}}=e;return{value:n,label:t,attributes:i,default:s}}))}(t);return function(e){const n=(0,c.l)(e,((e,n)=>e.value===n.value));if(n.length>0)throw new Error(`Docusaurus error: Duplicate values "${n.map((e=>e.value)).join(", ")}" found in <Tabs>. Every value needs to be unique.`)}(e),e}),[n,t])}function p(e){let{value:n,tabValues:t}=e;return t.some((e=>e.value===n))}function m(e){let{queryString:n=!1,groupId:t}=e;const s=(0,r.k6)(),a=function(e){let{queryString:n=!1,groupId:t}=e;if("string"==typeof n)return n;if(!1===n)return null;if(!0===n&&!t)throw new Error('Docusaurus error: The <Tabs> component groupId prop is required if queryString=true, because this value is used as the search param name. You can also provide an explicit value such as queryString="my-search-param".');return t??null}({queryString:n,groupId:t});return[(0,l._X)(a),(0,i.useCallback)((e=>{if(!a)return;const n=new URLSearchParams(s.location.search);n.set(a,e),s.replace({...s.location,search:n.toString()})}),[a,s])]}function f(e){const{defaultValue:n,queryString:t=!1,groupId:s}=e,a=h(e),[r,l]=(0,i.useState)((()=>function(e){let{defaultValue:n,tabValues:t}=e;if(0===t.length)throw new Error("Docusaurus error: the <Tabs> component requires at least one <TabItem> children component");if(n){if(!p({value:n,tabValues:t}))throw new Error(`Docusaurus error: The <Tabs> has a defaultValue "${n}" but none of its children has the corresponding value. Available values are: ${t.map((e=>e.value)).join(", ")}. If you intend to show no default tab, use defaultValue={null} instead.`);return n}const i=t.find((e=>e.default))??t[0];if(!i)throw new Error("Unexpected error: 0 tabValues");return i.value}({defaultValue:n,tabValues:a}))),[c,d]=m({queryString:t,groupId:s}),[f,x]=function(e){let{groupId:n}=e;const t=function(e){return e?`docusaurus.tab.${e}`:null}(n),[s,a]=(0,u.Nk)(t);return[s,(0,i.useCallback)((e=>{t&&a.set(e)}),[t,a])]}({groupId:s}),b=(()=>{const e=c??f;return p({value:e,tabValues:a})?e:null})();(0,o.Z)((()=>{b&&l(b)}),[b]);return{selectedValue:r,selectValue:(0,i.useCallback)((e=>{if(!p({value:e,tabValues:a}))throw new Error(`Can't select invalid tab value=${e}`);l(e),d(e),x(e)}),[d,x,a]),tabValues:a}}var x=t(2389);const b={tabList:"tabList__CuJ",tabItem:"tabItem_LNqP"};var g=t(5893);function j(e){let{className:n,block:t,selectedValue:i,selectValue:r,tabValues:o}=e;const l=[],{blockElementScrollPositionUntilNextRender:c}=(0,a.o5)(),u=e=>{const n=e.currentTarget,t=l.indexOf(n),s=o[t].value;s!==i&&(c(n),r(s))},d=e=>{let n=null;switch(e.key){case"Enter":u(e);break;case"ArrowRight":{const t=l.indexOf(e.currentTarget)+1;n=l[t]??l[0];break}case"ArrowLeft":{const t=l.indexOf(e.currentTarget)-1;n=l[t]??l[l.length-1];break}}n?.focus()};return(0,g.jsx)("ul",{role:"tablist","aria-orientation":"horizontal",className:(0,s.Z)("tabs",{"tabs--block":t},n),children:o.map((e=>{let{value:n,label:t,attributes:a}=e;return(0,g.jsx)("li",{role:"tab",tabIndex:i===n?0:-1,"aria-selected":i===n,ref:e=>l.push(e),onKeyDown:d,onClick:u,...a,className:(0,s.Z)("tabs__item",b.tabItem,a?.className,{"tabs__item--active":i===n}),children:t??n},n)}))})}function v(e){let{lazy:n,children:t,selectedValue:s}=e;const a=(Array.isArray(t)?t:[t]).filter(Boolean);if(n){const e=a.find((e=>e.props.value===s));return e?(0,i.cloneElement)(e,{className:"margin-top--md"}):null}return(0,g.jsx)("div",{className:"margin-top--md",children:a.map(((e,n)=>(0,i.cloneElement)(e,{key:n,hidden:e.props.value!==s})))})}function w(e){const n=f(e);return(0,g.jsxs)("div",{className:(0,s.Z)("tabs-container",b.tabList),children:[(0,g.jsx)(j,{...e,...n}),(0,g.jsx)(v,{...e,...n})]})}function y(e){const n=(0,x.Z)();return(0,g.jsx)(w,{...e,children:d(e.children)},String(n))}},1361:(e,n,t)=>{t.d(n,{j:()=>r});const i={description__trivia:"description__trivia_yesz"};var s=t(4625),a=t(5893);function r(e){let{children:n}=e;return(0,a.jsxs)("div",{className:i.description__trivia,children:[(0,a.jsx)("img",{src:s.Z}),(0,a.jsx)("div",{children:n})]})}},4625:(e,n,t)=>{t.d(n,{Z:()=>i});const i=t.p+"assets/images/izanami_compressed-659337582c1667d92966e42fa1bfea3f.webp"},1151:(e,n,t)=>{t.d(n,{Z:()=>o,a:()=>r});var i=t(7294);const s={},a=i.createContext(s);function r(e){const n=i.useContext(a);return i.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function o(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(s):e.components||s:r(e.components),i.createElement(a.Provider,{value:n},e.children)}}}]);