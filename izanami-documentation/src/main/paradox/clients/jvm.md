# Java & Scala 

@@toc { depth=3 }

It's simple to build your client using the APIs. If you're application is built on jvm you can also use the built-in Izanami client. 

This client offer nice strategies for better performances. 


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

The config client is used to access the shared config in Izanami. 

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

 
## Features client


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

#### The smart cache strategy 


When you choose the smart cache, you have to provide patterns : 

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



 
## Experiments client
 
### Setup the client  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #experiment-client }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #experiment-client }

For experiments, there is only two strategies available : fetch or dev.

### Mark variants 

#### Get a variant for a client 

Setup the client  

Java
:   @@snip [client.java](../../../../../izanami-clients/jvm/src/test/java/izanami/javadsl/IzanamiTest.java) { #get-variant }

Scala
:   @@snip [client.scala](../../../../../izanami-clients/jvm/src/test/scala/izanami/experiments/FetchExperimentsClientStrategySpec.scala) { #get-variant }



## Exposing izanami with a Proxy 