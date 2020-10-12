# From Binaries

Binaries are the recommended way to run Izanami. 

```
wget --quiet 'https://dl.bintray.com/maif/binaries/izanami.jar/latest/izanami.jar'
``` 

Or 

```
wget --quiet 'https://dl.bintray.com/maif/binaries/izanami-dist/latest/izanami-dist.zip'
``` 


You can get 

* izanami.jar: a fat jar 
* izanami.zip: <a href="http://www.scala-sbt.org/sbt-native-packager/formats/universal.html" target="_blanck">"a native java package"</a>

## Run izanami.jar 

To run izanami.jar, you will need JDK 11. 

Then just use :

```bash
java -jar izanami.jar 
``` 
   
## Run izanami.zip

To run izanami.zip, you will need JDK 11.

First unzip the archive : 

```bash
unzip izanami-dist.zip 
```

And then 

```bash
cd izanami/

bin/izanami
```

Or with windows

```dos
cd izanami/

./bin/izanami.bat  
```

