# Izanami examples 

This folder contains Izanami app examples. 

There is two backend : 
 
 * example-play: a play scala app  
 * example-spring: a springboot java app
 
And two frontend : 
 * javascript-angular: an ui written with angular   
 * javascript-react: an ui written with react
 
To run the demo you have to choose your favorite backend and frontend. 
The default frontend is react in both backend but you can change it in the configuration file: 

 * `conf/application.conf`: for the play app 
 * `src/main/resources/application.yml`: for the springboot app
 

In order to use Izanami, you can user docker compose with the file at the root of this folder : 

```
docker-compose -f docker-compose.izanami.yml up 
```

This will start an Izanami server listening on the port `9000` and using 

 * `redis` to store configs, feature and experiments
 * `elasticsearch` to store experiment events
 * `kafka` to store events 


You can find the springboot tutorial at this link https://maif.github.io/izanami/manual/tutorials/spring.html. 

## Publish Izanami client on local repository

```
sbt 'project jvm' '~publishLocal'
```

## Run the play app 

```
git clone https://github.com/MAIF/izanami.git
cd izanami 
sbt -Dhttp.port=8080 'project example-play' '~run'
```

## Run the springboot app 

```
git clone https://github.com/MAIF/izanami.git
cd izanami 
sbt 'project example-spring' '~reStart'
```

or 

```
git clone https://github.com/MAIF/izanami.git
cd izanami/example/example-spring
./gradlew bootRun 
```

## Use the react frontend

```
cd izanami/example/javascript-react
yarn install 
yarn start 
```

## Use the angular frontend

```
cd izanami/example/javascript-angular
yarn install 
yarn start 
```
