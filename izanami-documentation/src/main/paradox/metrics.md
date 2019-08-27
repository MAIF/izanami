# Metrics

Izanami provide metrics that are available with : 

 * console
 * log 
 * http (endpoint `/metrics`)
 * kafka
 * elasticsearch 

The metrics are available in json or prometheus format. 


You can configure the metrics using env variable or system properties: 


```bash
bin/izanami \ 
    -Dizanami.metrics.verbose=true \
    -Dizanami.metrics.includeCount=true \
    "-Dizanami.metrics.refresh=1 minute" \ 
    -Dizanami.metrics.console.enabled=true \
    "-Dizanami.metrics.console.interval=20 seconds" \
    -Dizanami.metrics.log.enabled=true \
    "-Dizanami.metrics.log.interval=20 seconds" \
    -Dizanami.metrics.http.format=json \
    -Dizanami.metrics.kafka.enabled=true \
    -Dizanami.metrics.kafka.topic=izanami-metrics \
    "-Dizanami.metrics.kafka.pushInterval=20 seconds" \
    -Dizanami.metrics.kafka.format=prometheus \
    -Dizanami.metrics.elastic.enabled=true \
    "-Dizanami.metrics.elastic.index='izanami-metrics-'yyyy-MM-dd" \
    "-Dizanami.metrics.elastic.pushInterval=20 seconds"     
```

Or 

```bash
export METRICS_VERBOSE=true 
export METRICS_COUNT=true 
export METRICS_COUNT_REFRESH_INTERVAL=20 seconds 
export METRICS_CONSOLE_ENABLED=true 
export METRICS_CONSOLE_INTERVAL=20 seconds 
export METRICS_LOG_ENABLED=true 
export METRICS_LOG_INTERVAL=20 seconds 
export METRICS_HTTP_FORMAT=prometheus 
export METRICS_KAFKA_ENABLED=true 
export METRICS_KAFKA_TOPIC=izanami-metrics 
export METRICS_KAFKA_INTERVAL=20 seconds
export METRICS_KAFKA_FORMAT=json
export METRICS_ELASTIC_ENABLED=true 
export METRICS_ELASTIC_INDEX='izanami-metrics-'yyyy-MM-dd 
export METRICS_ELASTIC_INTERVAL=20 seconds
bin/izanami 
```
