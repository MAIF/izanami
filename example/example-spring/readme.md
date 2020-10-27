# Sample springboot application using izanami

This app is a springboot and react application using izanami for feature flipping, A/B testing et shared configuration.  

## Run application 

### Build javascript 
```bash
cd example/javascript-react 
yarn install 
yarn build 
```
or In dev mode 

```bash
cd example/javascript-react 
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

Publish izanami client on local repository
```bash
sbt 'project jvm' '~publishLocal'
```

Start sample
```bash
sbt 'project example-spring' '~reStart'
```

## With Otoroshi 

Use Account : 
`demo@admin.io/demoIzaOto`

Update the `/etc/hosts` file with : 
```
127.0.0.1  localhost www.mytvshow.demo otoroshi-api.mytvshow.demo otoroshi.mytvshow.demo otoroshi-admin-internal-api.mytvshow.demo 
```

```
cd example/example-spring
```

Open the file `otoroshi.json` and replace `MY_IP` with your IP address. 

And then launch the following docker command

```
cd example/example-spring

docker run -p "8081:8080" \
    -v $(pwd):/usr/app/otoroshi/leveldb \
    --env "CLAIM_SHAREDKEY=otoroshisharedkey" \
    -it maif/otoroshi:1.0.2 \
    -Dapp.domain=mytvshow.demo \
    -Dplay.http.session.domain=.mytvshow.demo \
    -Dapp.importFrom="/usr/app/otoroshi/leveldb/otoroshi.json"
```



Follow the tutorial [here](https://maif.github.io/izanami/manual/tutorials/spring.html)


