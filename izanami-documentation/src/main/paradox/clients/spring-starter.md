# Spring starter 

## Configuration 

### Settings 

|Key                                          |Description                                                                                                          |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------------------| 
| `izanami.host`                              | server address. eg http://localhost:9000                                                                            | 
| `izanami.client-id`                         | client id                                                                                                           | 
| `izanami.client-secret`                     | client secret                                                                                                       | 
| `izanami.client-id-header-name`             | client id header name                                                                                               | 
| `izanami.client-secret-header-name`         | client secret header name                                                                                           | 
| `izanami.backend`                           | Undefined (default) or SseBackend                                                                                   | 
| `izanami.page-size`                         | Size of the pages fetched by the client (default 200)                                                               | 
| `izanami.zone-id`                           | Zone id for dates (default: Paris/Europe)                                                                           | 
| `izanami.dispatcher`                        | The akka dispatcher                                                                                                 | 
| `izanami.feature.strategy.type`             | `DevStrategy`, `FetchStrategy`, `FetchWithCacheStrategy`, `CacheWithSseStrategy` or `CacheWithPollingStrategy`      | 
| `izanami.feature.strategy.error-strategy`   | `Crash` or `RecoverWithFallback` (default)                                                                          | 
| `izanami.feature.strategy.duration`         | `FetchWithCacheStrategy` duration of the cache. ex: `1 second` or `5 minutes`                                       | 
| `izanami.feature.strategy.max-element`      | `FetchWithCacheStrategy` max elements in cache                                                                      | 
| `izanami.feature.strategy.polling-interval` | `CacheWithSseStrategy` or `CacheWithPollingStrategy` polling interval (optional for `CacheWithSseStrategy`), ex: `1 second` or `5 minutes` | 
| `izanami.feature.strategy.patterns`         | `CacheWithSseStrategy` or `CacheWithPollingStrategy` pattern for keys to keep in cache                              | 
| `izanami.feature.fallback`                  | a json array of features used as fallbacks                                                                          | 
| `izanami.feature.autocreate`                | `true` or `false` (default)                                                                                         | 
| `izanami.config.strategy.type`              | `DevStrategy`, `FetchStrategy`, `FetchWithCacheStrategy`, `CacheWithSseStrategy` or `CacheWithPollingStrategy`      | 
| `izanami.config.strategy.error-strategy`    | `Crash` or `RecoverWithFallback` (default)                                                                          | 
| `izanami.config.strategy.duration`          | `FetchWithCacheStrategy` duration of the cache. ex: `1 second` or `5 minutes`                                       | 
| `izanami.config.strategy.max-element`       | `FetchWithCacheStrategy` max elements in cache                                                                      | 
| `izanami.config.strategy.polling-interval`  | `CacheWithSseStrategy` or `CacheWithPollingStrategy` polling interval (optional for `CacheWithSseStrategy`), ex: `1 second` or `5 minutes` | 
| `izanami.config.strategy.patterns`          | `CacheWithSseStrategy` or `CacheWithPollingStrategy` pattern for keys to keep in cache                              | 
| `izanami.config.fallback`                   | a json array of configs used as fallbacks                                                                           | 
| `izanami.config.autocreate`                 | `true` or `false` (default)                                                                                         | 
| `izanami.experiment.fallback`               | a json array of experiments used as fallbacks                                                                       |  
| `izanami.experiment.strategy.type`          | `DevStrategy` or `FetchStrategy`                                                                                    | 


### Spring starter minimum config 

```
izanami:
  host: http://localhost:8080
  client-id: xxxx
  client-secret: xxxx
  feature:
    strategy:
      type: DevStrategy
  config:
    strategy:
      type: DevStrategy
  experiment:
    strategy:
      type: DevStrategy
```

### Spring starter full configuration 

```
izanami:
  host: http://localhost:8080
  client-id: xxxx
  client-secret: xxxx
  client-id-header-name: Izanami-Client
  client-secret-header-name: Izanami-Secret
  backend: SseBackend
  page-size: 5
  zone-id: America/Phoenix
  dispatcher: izanami.blocking-dispatcher
  feature:
    strategy:
      type: FetchWithCacheStrategy
      error-strategy: RecoverWithFallback
      duration: 2 minutes
      max-element: 5
    fallback: >
      [
        { "id": "mytvshows:season:markaswatched", "enabled": false },
        { "id": "mytvshows:providers:tvdb", "enabled": true },
        { "id": "mytvshows:providers:betaserie", "enabled": false },
        { "id": "mytvshows:providers:omdb", "enabled": false }
      ]
    autocreate: true
  config:
    fallback: >
      [
        { "id": "izanami:example:config", "value": { "emailProvider": "test" } }
      ]
    strategy:
      type: CacheWithSseStrategy
      error-strategy: Crash
      polling-interval: 1 minute
      patterns: [mytvshows:*, izanami:*]
    autocreate: true
  experiment:
    fallback: >
      [
        {
          "id": "mytvshows:gotoepisodes:button",
          "name": "Test button",
          "description": "Test button",
          "enabled": true,
          "variant": {
            "id": "A",
            "name": "Variant A",
            "description": "Variant A"
          }
        }
      ]
    strategy:
      type: DevStrategy
```

## Spring beans

The beans exposed by this starter are 

 * `IzanamiClient` : the root client 
 * `FeatureClient` : the feature client if feature settings are defined  
 * `ConfigClient` : the config client if feature settings are defined  
 * `ExperimentsClient` : the experiment client if feature settings are defined  


If the spring reactor is on the dependencies : 

 * `ReactiveConfigClient`: The reactive version of the feature client if feature settings are defined
 * `ReactiveExperimentClient`: The reactive version of the config client if feature settings are defined
 * `ReactiveFeatureClient`: The reactive version of the experiment client if feature settings are defined
 
 