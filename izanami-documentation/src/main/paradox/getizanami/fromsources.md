# From sources

To run izanami from sources, you will need : 

* git 
* JDK 11 
* SBT 
* Node.js 10
* yarn or npm 

First get the sources : 

```bash
git clone https://github.com/MAIF/izanami.git --depth=1
```


## Build the javascript 

Then go to the js folder : 

```bash
cd izanami-server/javascript
```

And then 

```bash
yarn install 
yarn build 
```

## Package the server 

Allow the compiler to use more memory : 

```bash
SBT_OPTS="-Xmx2G -Xss20M -XX:MaxMetaspaceSize=512M"
```

From the root folder 

### Build the native package 

```bash
sbt 'izanami-server/dist'
```

The build package is located on the folder `izanami-server/target/universal/`

### Build the fat jar

```bash
sbt 'izanami-server/assembly'
```

The build package is located on the folder `izanami-server/scala-2.12/izanami/`
