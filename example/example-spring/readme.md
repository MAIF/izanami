# Sample springboot application using izanami

This app is a springboot and react application using izanami for feature flipping, A/B testing et shared configuration.  

## Run application 

### Build javascript 
```bash
cd javascript 
yarn install 
yarn build 
```
or In dev mode 

```bash
cd javascript 
yarn install 
yarn start 
```

### Run springboot app 

In the root folder 

```bash
sbt 'project example-spring' 'run'  
```

or in dev mode 

add profile dev in `src/main/resources/application.yml` : 

```yaml
spring:
  profiles:
    active:
      - izanamiProd
      - dev

``` 

Run app with the watch mode (the app is restarted on changes)

In the root folder

```bash
sbt 'project example-spring' '~reStart'
```


Follow the tutorial [here](https://maif.github.io/izanami/manual/tutorials/spring.html)


