# Performances

Here is some benchmarks done on my machine (macbook pro with 16 GO of ram and a core I7 3,5 GHz)

## InMemory with db


Izanami run with :
```
izanami-server/target/universal/stage/bin/izanami  \
  -Dizanami.db.default=InMemoryWithDb \
  -Dizanami.db.inMemoryWithDb.db=Redis \
  -Dizanami.events.store=Kafka \
  -Dizanami.apikey.initialize.clientId=xxxx \
  -Dizanami.apikey.initialize.clientSecret=xxxx \
  -Dizanami.features.db.import=/Users/alexandredelegue/opun/izanami/izanami-benchmarks/data/features.ndjson \
  -Dlogger.file=/Users/alexandredelegue/opun/izanami/izanami-server/conf/prod-logger.xml
```

### List features :

 * 2 threads
 * 1000 concurrent connexions 

`wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features`
  
Results

```
Running 30s test @ http://localhost:9000/api/features
  2 threads and 1000 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency   233.39ms  471.06ms   9.92s    98.88%
    Req/Sec   434.07    307.73     0.99k    48.24%
  Latency Distribution
     50%  175.21ms
     75%  259.79ms
     90%  357.54ms
     99%    1.09s
  19713 requests in 30.04s, 23.10MB read
  Socket errors: connect 0, read 801, write 76, timeout 67
Requests/sec:    656.21
Transfer/sec:    787.58KB
```

#### With script evaluation 

Izanami is launched with 

``` 
izanami-server/target/universal/stage/bin/izanami  \
  -Dizanami.db.default=InMemoryWithDb \
  -Dizanami.db.inMemoryWithDb.db=Redis \
  -Dizanami.events.store=Kafka \
  -Dizanami.apikey.initialize.clientId=xxxx \
  -Dizanami.apikey.initialize.clientSecret=xxxx \
  -Dizanami.features.db.import=/Users/alexandredelegue/opun/izanami/izanami-benchmarks/data/features-script.ndjson \
  -Dizanami.script-dispatcher.thread-pool-executor.fixed-pool-size=250 \
  -Dlogger.file=/Users/alexandredelegue/opun/izanami/izanami-server/conf/prod-logger.xml
``` 
A pool of 250 thread is allocated for the script execution.  

``` 
wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features\?active\=true

Running 30s test @ http://localhost:9000/api/features?active=true
  2 threads and 1000 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.53s   947.58ms   9.92s    82.23%
    Req/Sec    20.66     20.81   130.00     89.25%
  Latency Distribution
     50%    1.35s
     75%    1.90s
     90%    2.50s
     99%    5.78s
  959 requests in 30.09s, 3.69MB read
  Socket errors: connect 0, read 914, write 12, timeout 25
Requests/sec:     31.87
Transfer/sec:    125.53KB
```

### Get one feature :

 * 2 threads
 * 1000 concurrent connexions

```
wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features/a:key:1002
Running 30s test @ http://localhost:9000/api/features/a:key:1002
  2 threads and 1000 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    91.82ms  386.27ms   5.24s    97.42%
    Req/Sec    12.85k     1.62k   22.52k    78.66%
  Latency Distribution
     50%   36.01ms
     75%   49.63ms
     90%   65.49ms
     99%    2.38s
  764420 requests in 30.08s, 129.03MB read
  Socket errors: connect 0, read 616, write 27, timeout 0
Requests/sec:  25411.92
Transfer/sec:      4.29MB
```

#### With a script evaluation :

Izanami is launched with 

``` 
izanami-server/target/universal/stage/bin/izanami  \
  -Dizanami.db.default=InMemoryWithDb \
  -Dizanami.db.inMemoryWithDb.db=Redis \
  -Dizanami.events.store=Kafka \
  -Dizanami.apikey.initialize.clientId=xxxx \
  -Dizanami.apikey.initialize.clientSecret=xxxx \
  -Dizanami.features.db.import=/Users/alexandredelegue/opun/izanami/izanami-benchmarks/data/features-script.ndjson \
  -Dizanami.script-dispatcher.thread-pool-executor.fixed-pool-size=250 \
  -Dlogger.file=/Users/alexandredelegue/opun/izanami/izanami-server/conf/prod-logger.xml
``` 
A pool of 250 thread is allocated for the script execution.  


```
wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features/a:key:1002/check
 
Running 30s test @ http://localhost:9000/api/features/a:key:1002/check
  2 threads and 1000 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency   388.17ms  427.25ms   9.88s    97.46%
    Req/Sec   262.79    250.31     1.27k    79.74%
  Latency Distribution
     50%  339.68ms
     75%  478.72ms
     90%  628.74ms
     99%  969.49ms
  14335 requests in 30.10s, 1.70MB read
  Socket errors: connect 0, read 1234, write 70, timeout 67
Requests/sec:    476.23
Transfer/sec:     57.67KB

```

## Redis 

Izanami run with :
```
izanami-server/target/universal/stage/bin/izanami  \
  -Dizanami.db.default=Redis \
  -Dizanami.events.store=Kafka \
  -Dizanami.apikey.initialize.clientId=xxxx \
  -Dizanami.apikey.initialize.clientSecret=xxxx \
  -Dizanami.features.db.import=/Users/alexandredelegue/opun/izanami/izanami-benchmarks/data/features.ndjson \
  -Dlogger.file=/Users/alexandredelegue/opun/izanami/izanami-server/conf/prod-logger.xml
``` 


### List features :

 * 2 threads
 * 1000 concurrent connexions 

`wrk -t2 -c1000 -d 30s --timeout 10s --latency -H "Izanami-Client-Id: xxxx" -H "Izanami-Client-Secret: xxxx" http://localhost:9000/api/features`
  
Results

```
Running 30s test @ http://localhost:9000/api/features
  2 threads and 1000 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     5.36s   760.49ms   6.16s    86.62%
    Req/Sec   335.98    406.80     1.74k    85.48%
  Latency Distribution
     50%    5.51s
     75%    5.81s
     90%    6.00s
     99%    6.11s
  4566 requests in 30.06s, 5.33MB read
  Socket errors: connect 0, read 718, write 27, timeout 0
Requests/sec:    151.87
Transfer/sec:    181.68KB
```

### Get one feature :

 * 2 threads
 * 1000 concurrent connexions

```
Running 30s test @ http://localhost:9000/api/features/a:key:1002
  2 threads and 1000 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency    79.81ms   23.54ms 641.12ms   84.72%
    Req/Sec     6.10k     1.37k    9.02k    72.81%
  Latency Distribution
     50%   75.93ms
     75%   87.53ms
     90%  102.16ms
     99%  153.50ms
  360663 requests in 30.08s, 60.88MB read
  Socket errors: connect 0, read 336, write 27, timeout 0
Requests/sec:  11988.19
Transfer/sec:      2.02MB
```
