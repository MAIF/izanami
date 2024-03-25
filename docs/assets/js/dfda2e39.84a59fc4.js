"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[5669],{9166:(e,n,d)=>{d.r(n),d.d(n,{assets:()=>c,contentTitle:()=>r,default:()=>o,frontMatter:()=>i,metadata:()=>l,toc:()=>h});var t=d(5893),s=d(1151);const i={title:"Listen to events"},r=void 0,l={id:"events",title:"Listen to events",description:"Izanami provide a way to listen what is happening inside. There is two to achieve that :",source:"@site/v1/14-events.mdx",sourceDirName:".",slug:"/events",permalink:"/izanami/v1/events",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:14,frontMatter:{title:"Listen to events"},sidebar:"defaultSidebar",previous:{title:"Key definition and best practices",permalink:"/izanami/v1/keys"},next:{title:"Metrics",permalink:"/izanami/v1/metrics"}},c={},h=[{value:"Events model",id:"events-model",level:2},{value:"Domains",id:"domains",level:3},{value:"Types",id:"types",level:3},{value:"Web hooks",id:"web-hooks",level:2},{value:"Server sent event",id:"server-sent-event",level:2},{value:"Replay Events",id:"replay-events",level:3}];function a(e){const n={a:"a",code:"code",h2:"h2",h3:"h3",img:"img",li:"li",p:"p",pre:"pre",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...(0,s.a)(),...e.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(n.p,{children:"Izanami provide a way to listen what is happening inside. There is two to achieve that :"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"SSE"}),"\n",(0,t.jsx)(n.li,{children:"Webhooks"}),"\n"]}),"\n",(0,t.jsx)(n.h2,{id:"events-model",children:"Events model"}),"\n",(0,t.jsx)(n.p,{children:"An event is json with the following structure :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-json",children:'{\n  "_id": 942711288101863450,\n  "type": "CONFIG_UPDATED",\n  "key": "demo:title",\n  "domain": "Config",\n  "payload": {\n    "id": "demo:title",\n    "value": "{\\n \\"title\\": \\"Changed title\\"\\n}"\n  },\n  "oldValue": {\n    "id": "demo:title",\n    "value": "{\\n \\"title\\": \\"Title\\"\\n}"\n  },\n  "timestamp": "2017-12-18T11:01:00.469"\n}\n'})}),"\n",(0,t.jsxs)(n.table,{children:[(0,t.jsx)(n.thead,{children:(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.th,{children:"Field"}),(0,t.jsx)(n.th,{children:"Description"})]})}),(0,t.jsxs)(n.tbody,{children:[(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"_id"})}),(0,t.jsx)(n.td,{children:"Unique id of the event. A recent id is greater than a previous"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"type"})}),(0,t.jsx)(n.td,{children:"The type of the event (see the chapter below)"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"key"})}),(0,t.jsx)(n.td,{children:"The key of the modified object."})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"domain"})}),(0,t.jsx)(n.td,{children:"The domain concerned by the event (see the chapter below)"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"payload"})}),(0,t.jsx)(n.td,{children:"The json of the updated object"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"oldValue"})}),(0,t.jsx)(n.td,{children:"The oldValue is optional and only present during an update."})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"timestamp"})}),(0,t.jsx)(n.td,{children:"The timestamp of the event"})]})]})]}),"\n",(0,t.jsxs)(n.p,{children:["The field ",(0,t.jsx)(n.code,{children:"oldValue"})," is optional and only present during an update."]}),"\n",(0,t.jsx)(n.h3,{id:"domains",children:"Domains"}),"\n",(0,t.jsxs)(n.table,{children:[(0,t.jsx)(n.thead,{children:(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.th,{children:"Domain"}),(0,t.jsx)(n.th,{children:"Description"})]})}),(0,t.jsxs)(n.tbody,{children:[(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:"Experiment"}),(0,t.jsx)(n.td,{children:"Events from experiments, bindings and experiment events"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:"ApiKey"}),(0,t.jsx)(n.td,{children:"Events from api keys"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:"Config"}),(0,t.jsx)(n.td,{children:"Events from configs"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:"Feature"}),(0,t.jsx)(n.td,{children:"Events from features"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:"User"}),(0,t.jsx)(n.td,{children:"Events from users"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:"Script"}),(0,t.jsx)(n.td,{children:"Events from scripts"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:"Webhook"}),(0,t.jsx)(n.td,{children:"Events from web hooks"})]})]})]}),"\n",(0,t.jsx)(n.h3,{id:"types",children:"Types"}),"\n",(0,t.jsxs)(n.table,{children:[(0,t.jsx)(n.thead,{children:(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.th,{children:"Types"}),(0,t.jsx)(n.th,{children:"Description"})]})}),(0,t.jsxs)(n.tbody,{children:[(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"CONFIG_CREATED"})}),(0,t.jsx)(n.td,{children:"When a config is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"CONFIG_UPDATED"})}),(0,t.jsx)(n.td,{children:"When a config is updated"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"CONFIG_DELETED"})}),(0,t.jsx)(n.td,{children:"When a config is deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"CONFIGS_DELETED"})}),(0,t.jsx)(n.td,{children:"When configs are deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"FEATURE_CREATED"})}),(0,t.jsx)(n.td,{children:"When a feature is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"FEATURE_UPDATED"})}),(0,t.jsx)(n.td,{children:"When a feature is updated"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"FEATURE_DELETED"})}),(0,t.jsx)(n.td,{children:"When a feature is deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"FEATURES_DELETED"})}),(0,t.jsx)(n.td,{children:"When features are deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"GLOBALSCRIPT_CREATED"})}),(0,t.jsx)(n.td,{children:"When a global script is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"GLOBALSCRIPT_UPDATED"})}),(0,t.jsx)(n.td,{children:"When a global script is updated"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"GLOBALSCRIPT_DELETED"})}),(0,t.jsx)(n.td,{children:"When a global script is deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"GLOBALSCRIPTS_DELETED"})}),(0,t.jsx)(n.td,{children:"When global scripts are deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"USER_CREATED"})}),(0,t.jsx)(n.td,{children:"When a user is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"USER_UPDATED"})}),(0,t.jsx)(n.td,{children:"When a user is updates"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"USER_DELETED"})}),(0,t.jsx)(n.td,{children:"When a user is deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"USERS_DELETED"})}),(0,t.jsx)(n.td,{children:"When users are deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"WEBHOOK_CREATED"})}),(0,t.jsx)(n.td,{children:"When a webhook is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"WEBHOOK_UPDATED"})}),(0,t.jsx)(n.td,{children:"When a webhook is updated"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"WEBHOOK_DELETED"})}),(0,t.jsx)(n.td,{children:"When a webhook is deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"WEBHOOKS_DELETED"})}),(0,t.jsx)(n.td,{children:"When webhooks are deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"APIKEY_CREATED"})}),(0,t.jsx)(n.td,{children:"When an apikey is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"APIKEY_UPDATED"})}),(0,t.jsx)(n.td,{children:"When an apikey is updated"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"APIKEY_DELETED"})}),(0,t.jsx)(n.td,{children:"When an apikey is deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"APIKEYS_DELETED"})}),(0,t.jsx)(n.td,{children:"When apikeys are created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"EXPERIMENT_CREATED"})}),(0,t.jsx)(n.td,{children:"When an experiment is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"EXPERIMENT_UPDATED"})}),(0,t.jsx)(n.td,{children:"When an experiment is updated"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"EXPERIMENT_DELETED"})}),(0,t.jsx)(n.td,{children:"When an experiment is deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"EXPERIMENTS_DELETED"})}),(0,t.jsx)(n.td,{children:"When experiments are deleted"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"VARIANT_BINDING_CREATED"})}),(0,t.jsx)(n.td,{children:"When an binding is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"EXPERIMENT_VARIANT_EVENT_CREATED"})}),(0,t.jsx)(n.td,{children:"When an experiment event is created"})]}),(0,t.jsxs)(n.tr,{children:[(0,t.jsx)(n.td,{children:(0,t.jsx)(n.code,{children:"EXPERIMENT_VARIANT_EVENT_DELETED"})}),(0,t.jsx)(n.td,{children:"When an experiment event is deleted"})]})]})]}),"\n",(0,t.jsx)(n.h2,{id:"web-hooks",children:"Web hooks"}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{alt:"Webooks",src:d(3172).Z+"",width:"910",height:"308"})}),"\n",(0,t.jsx)(n.p,{children:"Web hooks allow you to get notified when events occurred in izanami. You have to register an endpoint in izanami,\nthe endpoint needs to be a POST api handling a json payload of the form :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-json",children:'{\n  "objectsEdited": [\n    {...},\n    {...}\n  ]\n}\n'})}),"\n",(0,t.jsx)(n.p,{children:"If the call to the registered endpoint failed too much, the registered web hook will be deactivated."}),"\n",(0,t.jsx)(n.h2,{id:"server-sent-event",children:"Server sent event"}),"\n",(0,t.jsxs)(n.p,{children:["Server sent event allow the server to push event to a client. The server keep a connection opened to send notifications.\nThe ",(0,t.jsx)(n.a,{href:"https://en.wikipedia.org/wiki/Server-sent_events",children:"Server-Sent Events"})," EventSource API is standardized as part of ",(0,t.jsx)(n.a,{href:"https://en.wikipedia.org/wiki/HTML5",children:"HTML5"})," by the ",(0,t.jsx)(n.a,{href:"https://en.wikipedia.org/wiki/World_Wide_Web_Consortium",children:"W3C"}),"."]}),"\n",(0,t.jsx)(n.p,{children:"Izanami expose server sent events endpoint to listen what is happening.\nThere is two endpoint:"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"/api/events?domains=Config,Feature&patterns=*"})," : a global listener"]}),"\n",(0,t.jsxs)(n.li,{children:[(0,t.jsx)(n.code,{children:"/api/events/Config?patterns=*"})," : a listener for a specific domain"]}),"\n"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-bash",children:"curl -X GET \\\n  'http://localhost:9000/api/events?domains=Config,Feature&patterns=*' \\\n  -H 'Content-Type: application/json' \\\n  -H 'Izanami-Client-Id: xxxx' \\\n  -H 'Izanami-Client-Secret: xxxx'\n"})}),"\n",(0,t.jsx)(n.p,{children:"Will return"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:'id: 942740804979392517\ndata: {"_id":942740804979392517,"type":"FEATURE_UPDATED","key":"drop:hbomb:in:random:place","domain":"Feature","payload":{"id":"drop:hbomb:in:random:place","enabled":false,"parameters":{"releaseDate":"11/01/2018 09:58:00"},"activationStrategy":"RELEASE_DATE"},"timestamp":"2017-12-18T13:58:17.841","oldValue":{"id":"drop:hbomb:in:random:place","enabled":false,"parameters":{"releaseDate":"11/01/2018 09:58:00"},"activationStrategy":"RELEASE_DATE"}}\n'})}),"\n",(0,t.jsx)(n.h3,{id:"replay-events",children:"Replay Events"}),"\n",(0,t.jsxs)(n.p,{children:["If you're using kafka as a data store for events, you can replay events from the last day using the ",(0,t.jsx)(n.code,{children:"Last-Event-Id"})," header."]}),"\n",(0,t.jsxs)(n.p,{children:["With this request, the event after the id ",(0,t.jsx)(n.code,{children:"942740804979392517"})," will be replayed."]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-bash",children:"curl -X GET \\\n  'http://localhost:9000/api/events?domains=Config,Feature&patterns=*' \\\n  -H 'Content-Type: application/json' \\\n  -H 'Izanami-Client-Id: xxxx' \\\n  -H 'Izanami-Client-Secret: xxxx' \\\n  -H 'Last-Event-Id: 942740804979392517'\n"})}),"\n",(0,t.jsx)(n.p,{children:"The replay can be used for failure handling while listening to events.\nIf the client lose the connection or crash, it can specify the last event id to get the event that append since the failure."})]})}function o(e={}){const{wrapper:n}={...(0,s.a)(),...e.components};return n?(0,t.jsx)(n,{...e,children:(0,t.jsx)(a,{...e})}):a(e)}},3172:(e,n,d)=>{d.d(n,{Z:()=>t});const t=d.p+"assets/images/events-webhooks-5680e08c4a1b053e1efb050ee39d028d.png"},1151:(e,n,d)=>{d.d(n,{Z:()=>l,a:()=>r});var t=d(7294);const s={},i=t.createContext(s);function r(e){const n=t.useContext(i);return t.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function l(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(s):e.components||s:r(e.components),t.createElement(i.Provider,{value:n},e.children)}}}]);