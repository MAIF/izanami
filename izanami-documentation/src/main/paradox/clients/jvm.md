# Java & Scala 

@@toc { depth=3 }

It's simple to build your client using the APIs. If you're application is built on jvm you can also use the built-in Izanami client. 

This client offer nice strategies for better performances. 


You need to add repository
 
Graddle
:   
```graddle
    repositories {
        jcenter()
        maven {
            url 'https://raw.githubusercontent.com/mathieuancelin/json-lib-javaslang/master/repository/releases/'
        }
    }
```  

Sbt
:   
```sbt
    resolvers ++= Seq(
      "jsonlib-repo" at "https://raw.githubusercontent.com/mathieuancelin/json-lib-javaslang/master/repository/releases",
      Resolver.jcenterRepo
    )
```

Add the following dependency to your project 

@@dependency[sbt,Maven,Gradle] {
  group=fr.maif
  artifact=izanami-client_$scalaBinaryVersion$
  version=$version$
}

The client can be used in java or scala. There is two distinct dsl. Be sure to import the correct one : 

Java
:  
```java
import izanami.*;
import izanami.javadsl.*;
```

Scala
:   
```scala
import izanami._
import izanami.scaladsl._
```

Izanami client is built with  

* [Scala](https://www.scala-lang.org/): As programming language  
* [Akka](https://akka.io/docs/): to handle global state, scheduler ... 
* [Akka http](https://doc.akka.io/docs/akka-http/current/scala/http/index.html): for http request, sse ...

The scaladsl rely on :

* [play json](https://github.com/playframework/play-json): for json handling 

The javadsl rely on : 

* [vavr](http://www.vavr.io/): For functional structures like future, either, option ...
* [play json java](https://github.com/mathieuancelin/json-lib-javaslang): For json handling


## Setup the Izanami client  

The first thing to do is to create a client. The client own the shared http client between config client, feature client and the experiment client.

You need to create a single client for all your application. 
 
 
Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #configure-client }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/ClientSpec.scala) { #configure-client }


| Field                   | Description                                                                                     |
|-------------------------|-------------------------------------------------------------------------------------------------|
|`clientId`               | The client id to access izanami APIs see @ref[Manage APIs keys](../ui.md#manage-api-keys)       |
|`clientSecretIdName`     | A custom header for the client id                                                               |
|`clientSecret`           | The client secret to access izanami APIs see @ref[Manage APIs keys](../ui.md#manage-api-keys)   |
|`clientSecretHeaderName` | A custom header for the client secret                                                           |
|`sseBackend`             | Enable sse to get events from the server                                                        |
|`dispatcher`             | Reference a dispatcher to manage thread pool                                                    |
|`pageSize`               | Change the size of the pages when fetching from the server                                      |
|`zoneId`                 | Zone Id to handle date                                                                          |


## Configs client

The config client is used to access the shared config in Izanami. To understand how configs work, just visit this @see[page](../configs/index.md)

### Setup the client  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #configure-config-client }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #config-client }

When you set up a client you have to choose a strategy : 


| Strategy                      | Description                                   |
| ----------------------------- | --------------------------------------------- |
| Fetch                         | Call izanami for each request                 |
| Fetch with cache              | Keep response in cache                        |
| Smart cache with polling      | Keep data in memory and poll izanami to refresh the cache asynchronously.  |
| Smart cache with sse          | Keep data in memory and refresh the cache with the events from the izanami server. |


#### The fetch strategy 

The fetch strategy will call izanami for each request. 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #config-fetch }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #config-client }

#### The fetch with cache strategy 

The fetch with cache will do dumb cache by http call. You have to provide a the max elements in cache and a TTL.  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #config-fetch-cache }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchWithCacheConfigClientSpec.scala) { #config-fetch-cache }


#### The smart cache strategy 


When you choose the smart cache, you have to provide patterns : 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #smart-cache }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/SmartCacheConfigClientSpec.scala) { #smart-cache }


The client will cache all the configs matching this patterns. 

* With a poll strategy, the client will request the server to get change so you have to set a delay. 
* With a SSE strategy, the client will listen events from the server to refresh the cache. 

@@@ warning
There is no TTL using this strategy so you have to choose the right patterns to be sure that all datas fit in memory. 
@@@

#### Handling errors 

An error handling strategy could be provided. You can choose between :

 * `RecoverWithFallback`: If the call crash, the fallback is used 
 * `Crash`: The call will finish on error if an error occured while evaluating the feature, config or experiment.  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #config-error-strategy }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #config-error-strategy }

### Client usage 

#### Get configs for a pattern 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #all-configs }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #all-configs }

#### Get one config 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #one-config }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #one-config }

#### Create / Update / Delete configs 

Create config using json 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #create-config-json }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #create-config-json }


Create config using a config object  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #create-config }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #create-config }

Update config using json 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #update-config-json }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #update-config-json }

Update config using a config object  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #update-config }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #update-config }

Delete a config

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #delete-config }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #delete-config }


#### Autocreate configs

You can autocreate configs that are define as fallback. To enable this you need set the `autocreate` parameter when the client is created. 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #config-autocreate }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/configs/FetchConfigClientSpec.scala) { #config-autocreate }

 
## Features client

To understand how features work, just visit this @see[page](../features/index.md)

### Setup the client

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #configure-feature-client }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #feature-client }


When you set up a client you have to choose a strategy : 


| Strategy                      | Description                                   |
| ----------------------------- | --------------------------------------------- |
| Fetch                         | Call izanami for each request                 |
| Fetch with cache              | Keep response in cache                        |
| Smart cache with polling      | Keep data in memory and poll izanami to refresh the cache asynchronously. The features that need a context are not cached because it can needs a huge amount of memory |
| Smart cache with sse          | Keep data in memory and refresh the cache with the events from the izanami server. The features that need a context are not cached because it can needs a huge amount of memory |

#### The fetch strategy 

The fetch strategy will call izanami for each request 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #configure-feature-client }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #fetch-strategy }


#### The fetch with cache strategy 

The fetch with cache will do dumb cache by http call. You have to provide a the max elements in cache and a TTL.  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #feature-fetch-cache }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchWithCacheFeatureClientSpec.scala) { #fetch-cache }


#### The smart cache strategy 


When you choose the smart cache, you have to provide patterns to select the keys that will be in cache: 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #feature-smart-cache }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/SmartCacheFeatureClientSpec.scala) { #smart-cache }

The client will cache all the configs matching this patterns. 

* With a poll strategy, the client will request the server to get change so you have to set a delay. 
* With a SSE strategy, the client will listen events from the server to refresh the cache. 

@@@ note
The feature that need a context to be evaluated are not cached. The cache is used only for simple features or feature with release date.  
@@@

@@@ warning
There is no TTL using this strategy so you have to choose the right patterns to be sure that all datas fit in memory. 
@@@

#### Handling errors 

An error handling strategy could be provided. You can choose between :

 * `RecoverWithFallback`: If the call crash, the fallback is used 
 * `Crash`: The call will finish on error if an error occured while evaluating the feature, config or experiment.  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #error-strategy }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #error-strategy }

### Client usage

#### List features 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #features-list }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #list }


#### Check feature

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #features-check }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #check }

If the feature needs a context to be evaluated: 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #features-check-context }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #check-context }


#### Conditional code on feature

This execute a code and return a value if a feature is active: 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #features-check-conditional }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #check-conditional }

Or with a context 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #features-check-conditional-context }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #check-conditional-context }

#### Create / update / delete 

With the client you can mutate features. 

Create with raw data: 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #create-feature-json }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #create-feature-json }

Or with a feature object: 
Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #create-feature }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #create-feature }

Update a feature : 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #update-feature }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #update-feature }

Delete a feature : 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #delete-feature }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #delete-feature }


You can also activate or deactivate a feature 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #activate-feature }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #activate-feature }


#### Autocreate features

You can autocreate features that are define as fallback. To enable this you need set the `autocreate` parameter when the client is created. 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #feature-client-autocreate }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/features/FetchFeatureClientSpec.scala) { #feature-client-autocreate }


## Experiments client
 
To understand how experiments work, just visit this @see[page](../experiments/index.md) 
 
### Setup the client  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #experiment-client }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #experiment-client }

For experiments, there is only two strategies available : fetch or dev.

### Variants 

#### Get a variant for a client 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #get-variant }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #get-variant }

#### Mark variant displayed


Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #displayed-variant }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #displayed-variant }

#### Mark variant won

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #won-variant }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #won-variant }

### Work with experiment 

#### Get the experiment 
Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #get-experiment }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #get-experiment }


Once you get the experiment, you can get a variant for a client, mark variant displayed or mark variant won : 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #an-experiment }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #an-experiment }



### Experiment tree 

You can get the experiments tree with associated variant for a client : 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #experiment-tree }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #experiment-tree }

 



## Exposing izanami with a Proxy 

When you have to use Izanami from the client side, you can't call Izanami directly from the browser because it means the API keys are exposed to anyone. 

The best solution is to use your backend as a proxy. You can do this with the jvm client. 


 

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #proxy }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/proxy/ProxySpec.scala) { #proxy }

 