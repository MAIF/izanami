# For Developers

## Run React example with local react-izanami module

```bash
cd izanami/example/javascript-react
yarn run link-dev-react-izanami
```
> :warning: when your task is finished, run unlink-dev-react-izanami to stop the local resolution of dependency.

## Starting the server for developers 

```bash 
git clone https://github.com/MAIF/izanami.git --depth=1
```

### Starting the js server 

Then go to the js folder : 

```bash
cd izanami-server/javascript
```

And then 

```bash
yarn install 
yarn start  
```

### Starting play server 

```sbtshell
sbt 
> ~izanami-server/run 
```

### Use a specific DB 

You use a specific data base using docker 

#### Redis

```bash 
docker rm redis-iznanami && docker run --name redis-iznanami -v $(pwd)/redisdata:/data -p 6379:6379 redis
```

```sbtshell
sbt 
> ~izanami-server/run -Dizanami.db.default=Redis
```

#### Elasticsearch 
```bash 
docker rm elasticsearch && docker run --name elasticsearch -e "xpack.security.enabled=false" -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" -e "discovery.type=single-node" -p 9200:9200 docker.elastic.co/elasticsearch/elasticsearch:7.11.1
```


```sbtshell
sbt 
> ~izanami-server/run -Dizanami.db.default=Elastic
```


#### AWS DynamoDB 
```bash 
docker rm dynamodb && docker run --name dynamodb -p 8000:8000 amazon/dynamodb-local
```


```sbtshell
sbt 
> ~izanami-server/run -Dizanami.db.default=Dynamo -Dizanami.db.dynamo.host=localhost -Dizanami.db.dynamo.port=8000
```

### Kafka as Event store 

```bash
docker run -p 2181:2181 -p 9092:9092 --env ADVERTISED_HOST=127.0.0.1 --env ADVERTISED_PORT=9092 --env AUTO.CREATE.TOPICS.ENABLE spotify/kafka
```

```sbtshell
sbt 
> ~izanami-server/run -Dizanami.events.store=Kafka 
```

### Akka as Event store

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

