"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[2258],{1688:(e,n,i)=>{i.r(n),i.d(n,{assets:()=>o,contentTitle:()=>r,default:()=>m,frontMatter:()=>s,metadata:()=>c,toc:()=>d});var t=i(5893),a=i(1151);const s={},r="Performances",c={id:"performances",title:"Performances",description:"Here is some benchmarks done on my machine (macbook pro with 16 GO of ram and a core I7 3,5 GHz)",source:"@site/v1/19-performances.mdx",sourceDirName:".",slug:"/performances",permalink:"/izanami/v1/performances",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:19,frontMatter:{},sidebar:"defaultSidebar",previous:{title:"Oauth2 with keycloak",permalink:"/izanami/v1/tutorials/oauth2"},next:{title:"For Developers",permalink:"/izanami/v1/developers"}},o={},d=[{value:"InMemory with db",id:"inmemory-with-db",level:2},{value:"List features :",id:"list-features-",level:3},{value:"With script evaluation",id:"with-script-evaluation",level:4},{value:"Get one feature :",id:"get-one-feature-",level:3},{value:"With a script evaluation :",id:"with-a-script-evaluation-",level:4},{value:"Redis",id:"redis",level:2},{value:"List features :",id:"list-features--1",level:3},{value:"Get one feature :",id:"get-one-feature--1",level:3}];function l(e){const n={code:"code",h1:"h1",h2:"h2",h3:"h3",h4:"h4",li:"li",p:"p",pre:"pre",ul:"ul",...(0,a.a)(),...e.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(n.h1,{id:"performances",children:"Performances"}),"\n",(0,t.jsx)(n.p,{children:"Here is some benchmarks done on my machine (macbook pro with 16 GO of ram and a core I7 3,5 GHz)"}),"\n",(0,t.jsx)(n.h2,{id:"inmemory-with-db",children:"InMemory with db"}),"\n",(0,t.jsx)(n.p,{children:"Izanami run with :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"izanami-server/target/universal/stage/bin/izanami  \\\n  -Dizanami.db.default=InMemoryWithDb \\\n  -Dizanami.db.inMemoryWithDb.db=Redis \\\n  -Dizanami.events.store=Kafka \\\n  -Dizanami.apikey.initialize.clientId=xxxx \\\n  -Dizanami.apikey.initialize.clientSecret=xxxx \\\n  -Dizanami.features.db.import=/Users/alexandredelegue/opun/izanami/izanami-benchmarks/data/features.ndjson \\\n  -Dlogger.file=/Users/alexandredelegue/opun/izanami/izanami-server/conf/prod-logger.xml\n"})}),"\n",(0,t.jsx)(n.h3,{id:"list-features-",children:"List features :"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"2 threads"}),"\n",(0,t.jsx)(n.li,{children:"1000 concurrent connexions"}),"\n"]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.code,{children:'wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features'})}),"\n",(0,t.jsx)(n.p,{children:"Results"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"Running 30s test @ http://localhost:9000/api/features\n  2 threads and 1000 connections\n  Thread Stats   Avg      Stdev     Max   +/- Stdev\n    Latency   233.39ms  471.06ms   9.92s    98.88%\n    Req/Sec   434.07    307.73     0.99k    48.24%\n  Latency Distribution\n     50%  175.21ms\n     75%  259.79ms\n     90%  357.54ms\n     99%    1.09s\n  19713 requests in 30.04s, 23.10MB read\n  Socket errors: connect 0, read 801, write 76, timeout 67\nRequests/sec:    656.21\nTransfer/sec:    787.58KB\n"})}),"\n",(0,t.jsx)(n.h4,{id:"with-script-evaluation",children:"With script evaluation"}),"\n",(0,t.jsx)(n.p,{children:"Izanami is launched with"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"izanami-server/target/universal/stage/bin/izanami  \\\n  -Dizanami.db.default=InMemoryWithDb \\\n  -Dizanami.db.inMemoryWithDb.db=Redis \\\n  -Dizanami.events.store=Kafka \\\n  -Dizanami.apikey.initialize.clientId=xxxx \\\n  -Dizanami.apikey.initialize.clientSecret=xxxx \\\n  -Dizanami.features.db.import=/Users/alexandredelegue/opun/izanami/izanami-benchmarks/data/features-script.ndjson \\\n  -Dizanami.script-dispatcher.thread-pool-executor.fixed-pool-size=250 \\\n  -Dlogger.file=/Users/alexandredelegue/opun/izanami/izanami-server/conf/prod-logger.xml\n"})}),"\n",(0,t.jsx)(n.p,{children:"A pool of 250 thread is allocated for the script execution."}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:'wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features\\?active\\=true\n\nRunning 30s test @ http://localhost:9000/api/features?active=true\n  2 threads and 1000 connections\n  Thread Stats   Avg      Stdev     Max   +/- Stdev\n    Latency     1.53s   947.58ms   9.92s    82.23%\n    Req/Sec    20.66     20.81   130.00     89.25%\n  Latency Distribution\n     50%    1.35s\n     75%    1.90s\n     90%    2.50s\n     99%    5.78s\n  959 requests in 30.09s, 3.69MB read\n  Socket errors: connect 0, read 914, write 12, timeout 25\nRequests/sec:     31.87\nTransfer/sec:    125.53KB\n'})}),"\n",(0,t.jsx)(n.h3,{id:"get-one-feature-",children:"Get one feature :"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"2 threads"}),"\n",(0,t.jsx)(n.li,{children:"1000 concurrent connexions"}),"\n"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:'wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features/a:key:1002\nRunning 30s test @ http://localhost:9000/api/features/a:key:1002\n  2 threads and 1000 connections\n  Thread Stats   Avg      Stdev     Max   +/- Stdev\n    Latency    91.82ms  386.27ms   5.24s    97.42%\n    Req/Sec    12.85k     1.62k   22.52k    78.66%\n  Latency Distribution\n     50%   36.01ms\n     75%   49.63ms\n     90%   65.49ms\n     99%    2.38s\n  764420 requests in 30.08s, 129.03MB read\n  Socket errors: connect 0, read 616, write 27, timeout 0\nRequests/sec:  25411.92\nTransfer/sec:      4.29MB\n'})}),"\n",(0,t.jsx)(n.h4,{id:"with-a-script-evaluation-",children:"With a script evaluation :"}),"\n",(0,t.jsx)(n.p,{children:"Izanami is launched with"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"izanami-server/target/universal/stage/bin/izanami  \\\n  -Dizanami.db.default=InMemoryWithDb \\\n  -Dizanami.db.inMemoryWithDb.db=Redis \\\n  -Dizanami.events.store=Kafka \\\n  -Dizanami.apikey.initialize.clientId=xxxx \\\n  -Dizanami.apikey.initialize.clientSecret=xxxx \\\n  -Dizanami.features.db.import=/Users/alexandredelegue/opun/izanami/izanami-benchmarks/data/features-script.ndjson \\\n  -Dizanami.script-dispatcher.thread-pool-executor.fixed-pool-size=250 \\\n  -Dlogger.file=/Users/alexandredelegue/opun/izanami/izanami-server/conf/prod-logger.xml\n"})}),"\n",(0,t.jsx)(n.p,{children:"A pool of 250 thread is allocated for the script execution."}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:'wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features/a:key:1002/check\n \nRunning 30s test @ http://localhost:9000/api/features/a:key:1002/check\n  2 threads and 1000 connections\n  Thread Stats   Avg      Stdev     Max   +/- Stdev\n    Latency   388.17ms  427.25ms   9.88s    97.46%\n    Req/Sec   262.79    250.31     1.27k    79.74%\n  Latency Distribution\n     50%  339.68ms\n     75%  478.72ms\n     90%  628.74ms\n     99%  969.49ms\n  14335 requests in 30.10s, 1.70MB read\n  Socket errors: connect 0, read 1234, write 70, timeout 67\nRequests/sec:    476.23\nTransfer/sec:     57.67KB\n\n'})}),"\n",(0,t.jsx)(n.h2,{id:"redis",children:"Redis"}),"\n",(0,t.jsx)(n.p,{children:"Izanami run with :"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"izanami-server/target/universal/stage/bin/izanami  \\\n  -Dizanami.db.default=Redis \\\n  -Dizanami.events.store=Kafka \\\n  -Dizanami.apikey.initialize.clientId=xxxx \\\n  -Dizanami.apikey.initialize.clientSecret=xxxx \\\n  -Dizanami.features.db.import=/Users/alexandredelegue/opun/izanami/izanami-benchmarks/data/features.ndjson \\\n  -Dlogger.file=/Users/alexandredelegue/opun/izanami/izanami-server/conf/prod-logger.xml\n"})}),"\n",(0,t.jsx)(n.h3,{id:"list-features--1",children:"List features :"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"2 threads"}),"\n",(0,t.jsx)(n.li,{children:"1000 concurrent connexions"}),"\n"]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.code,{children:'wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features'})}),"\n",(0,t.jsx)(n.p,{children:"Results"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"Running 30s test @ http://localhost:9000/api/features\n  2 threads and 1000 connections\n  Thread Stats   Avg      Stdev     Max   +/- Stdev\n    Latency     5.36s   760.49ms   6.16s    86.62%\n    Req/Sec   335.98    406.80     1.74k    85.48%\n  Latency Distribution\n     50%    5.51s\n     75%    5.81s\n     90%    6.00s\n     99%    6.11s\n  4566 requests in 30.06s, 5.33MB read\n  Socket errors: connect 0, read 718, write 27, timeout 0\nRequests/sec:    151.87\nTransfer/sec:    181.68KB\n"})}),"\n",(0,t.jsx)(n.h3,{id:"get-one-feature--1",children:"Get one feature :"}),"\n",(0,t.jsxs)(n.ul,{children:["\n",(0,t.jsx)(n.li,{children:"2 threads"}),"\n",(0,t.jsx)(n.li,{children:"1000 concurrent connexions"}),"\n"]}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{children:"Running 30s test @ http://localhost:9000/api/features/a:key:1002\n  2 threads and 1000 connections\n  Thread Stats   Avg      Stdev     Max   +/- Stdev\n    Latency    79.81ms   23.54ms 641.12ms   84.72%\n    Req/Sec     6.10k     1.37k    9.02k    72.81%\n  Latency Distribution\n     50%   75.93ms\n     75%   87.53ms\n     90%  102.16ms\n     99%  153.50ms\n  360663 requests in 30.08s, 60.88MB read\n  Socket errors: connect 0, read 336, write 27, timeout 0\nRequests/sec:  11988.19\nTransfer/sec:      2.02MB\n"})})]})}function m(e={}){const{wrapper:n}={...(0,a.a)(),...e.components};return n?(0,t.jsx)(n,{...e,children:(0,t.jsx)(l,{...e})}):l(e)}},1151:(e,n,i)=>{i.d(n,{Z:()=>c,a:()=>r});var t=i(7294);const a={},s=t.createContext(a);function r(e){const n=t.useContext(s);return t.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function c(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:r(e.components),t.createElement(s.Provider,{value:n},e.children)}}}]);