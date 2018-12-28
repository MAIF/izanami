# Spring config

Izanami can be used as a spring config server. To define remote config you define configuration with keys like : 

*  `${rootPath}:${applicatioName}:${profileName}:spring-config` 
*  `${rootPath}:spring-profiles:${profileName}:spring-config` 
*  `${rootPath}:spring-globals:spring-config`  
 
The rootPath is a namespace to group keys for an application. This namespace is used to define the uri in the spring cloud config. 
For example if ${rootPath} is `myapp`, you will have this in the `bootstrap.yml` : 

```yaml
spring:
  cloud:
    config:
      uri: "http://localhost:9000/api/config-server/raw/mapp"
      username: xxxx
      password: xxxx
```

You can now define this key in Izanami

*  `mapp:application:default:spring-config`: ```{"key.key1": "A value 1", "key.key2": "A value 2"}``` 

And use it like this 

```java 

    @Value("${key.key1:Default value1}")
    private String key1;
      
    @Value("${key.key2}")
    private String key2;
```