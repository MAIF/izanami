# Configuration 


@@@ index

 * [Choose the database](database.md)
 * [All the settings](settings.md)

@@@ 

@@toc { depth=3 } 

Izanami is based on [play framework](https://www.playframework.com) where configuration is based on [hocon](https://github.com/lightbend/config). 

Izanami is highly configurable either by environment variable or java system properties. You can also override the entire configuration file if needed. 

You can override any property with   

```bash
java -Dizanami.db.default=Redis -jar izanami.jar 
```

Or with environment variable 

```bash
IZANAMI_DATABASE=Redis java -jar izanami.jar 
```

Or change the entire configuration file with
 
```bash
java -Dconfig.file=/path/to/my/config.conf -jar izanami.jar 
```

