# Izanami [![gitter-badge][]][gitter] [![travis-badge][]][travis]

[travis]:                https://travis-ci.org/MAIF/izanami
[travis-badge]:          https://travis-ci.org/MAIF/izanami.svg?branch=master
[gitter]:                    https://gitter.im/MAIF/izanami
[gitter-badge]:       https://badges.gitter.im/MAIF/izanami.svg

Izanami is a shared configuration service well-suited for micro-service architecture implementation. In addition to shared configuration, Izanami provides feature flipping and A/B Testing. Izanami provides a UI to allow non-tech users to toggle features and to handle A/B testing.

Izanami also provides first class integration. We provide Java, Scala, Node and React clients to integrate with your apps. We also provide webhook integration.


<img src="https://github.com/MAIF/izanami/raw/master/izanami-documentation/src/main/paradox/img/izanami.png"></img>


## Documentation 

See our [documentation](https://maif.github.io/izanami)

## Contribution & Maintainers 

Contributions are welcomed. 

Izanami is written in [scala](https://www.scala-lang.org/) using the [playframework](https://www.playframework.com/). 

### Starting the server for developers 

```bash 
git clone https://github.com/MAIF/izanami.git --depth=1
```

#### Start js server 

Then go to the js folder : 

```bash
cd izanami-server/javascript
```

And then 

```bash
yarn install 
yarn start  
```

#### Start play server 

```sbtshell
sbt 
> ~izanami-server/run 
```

#### Use a specific DB 

You use a specific data base using docker 

##### Cassandra 

```bash 
docker rm cassandra && docker run --name cassandra -e CASSANDRA_LISTEN_ADDRESS=127.0.0.1 -p 9042:9042 -p 7000:7000 cassandra:3.11
```

```sbtshell
sbt 
> ~izanami-server/run -Dizanami.db.default=Cassandra
```

##### Redis

```bash 
docker rm redis-iznanami && docker run --name redis-iznanami -v $(pwd)/redisdata:/data -p 6379:6379 redis
```

```sbtshell
sbt 
> ~izanami-server/run -Dizanami.db.default=Redis
```

##### Elasticsearch 
```bash 
docker rm elasticsearch && docker run --name elasticsearch -e "xpack.security.enabled=false" -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" -p 9200:9200 docker.elastic.co/elasticsearch/elasticsearch:5.5.3
```


```sbtshell
sbt 
> ~izanami-server/run -Dizanami.db.default=Elastic
```

#### Kafka as Event store 

```bash
docker run -p 2181:2181 -p 9092:9092 --env ADVERTISED_HOST=127.0.0.1 --env ADVERTISED_PORT=9092 --env AUTO.CREATE.TOPICS.ENABLE spotify/kafka
```

```sbtshell
sbt 
> ~izanami-server/run -Dizanami.events.store=Kafka 
```

#### Akka as Event store

First node 

```sbtshell
sbt 
> ~izanami-server/run -Dizanami.events.store=Distributed 
```

Second node 

```sbtshell
sbt 
> ~izanami-server/run -Dizanami.events.store=Distributed -Dcluster.akka.remote.netty.tcp.port=2552 -Dhttp.port=9001 
```

