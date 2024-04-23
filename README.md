# Izanami

<img src="./izanami-frontend/izanami.png" alt="image" width="300" height="auto">

This README is for anyone who would like to contribute to Izanami.

If you're interested in Izanami documentation, [it's here](https://maif.github.io/izanami/).

## Start application locally

### Izanami frontend

```sh
cd izanami-frontend
npm run dev
```

### Local database & misc tools

```sh
docker-compose rm -f && docker-compose up
```

### Izanami backend

```sh
sbt -jvm-debug 5005
~run -Dconfig.resource=dev.conf
```

Once everything is started, just browse to [localhost:3000](http://localhost:3000).


In a developement setup, it may be usefull to craft tokens with longer TTL

```
sbt -jvm-debug 5005
~run -Dconfig.resource=dev.conf -Dapp.sessions.TTL=604800
```

### Backend tests

To run test, you can either start Izanami and associated tooling (db, ...) with above commands or just run a suite / a test.

In fact, an Izanami instance and docker containers will be started by tests if none is running. This could be usefull for coverage / debug.

You'll need docker-compose installed locally to run tests like this, due to [this issue](https://github.com/testcontainers/testcontainers-java/issues/7239).

#### Colima setup

To run test without having starting docker-compose, you'll need these env variables to be set.

```sh
DOCKER_HOST=unix://${HOME}/.colima/default/docker.sock;
RYUK_CONTAINER_PRIVILEGED=true;
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```


## Package application

To package frontend :

```sh
cd izanami-frontend
npm run build
```

To package backend (make sure to package frontend first) : 

```sh
sbt "set test in assembly := {}" clean assembly
```

To start generated jar

```sh
java -Dconfig.resource=dev.conf -jar ./target/scala-2.13/izanami.jar
```

To build docker image (after packaging frontend and backends)

```sh
docker build -t izanami .
```
To test docker image 

```sh
docker run --env IZANAMI_PG_URI=postgresql://postgres:postgres@host.docker.internal:5432/postgres -p 9000:9000 izanami
```
