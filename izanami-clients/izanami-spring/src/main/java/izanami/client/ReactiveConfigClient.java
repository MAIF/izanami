package izanami.client;

import akka.Done;
import io.vavr.collection.Seq;
import izanami.javadsl.*;
import org.reactivecouchbase.json.JsValue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

public class ReactiveConfigClient {

    private final ConfigClient configClient;

    public ReactiveConfigClient(ConfigClient configClient) {
        this.configClient = configClient;
    }

    public Mono<Config> createConfig(Config config) {
        return Monos.fromFuture(() -> configClient.createConfig(config));
    }

    public Mono<JsValue> createConfig(String id, JsValue config) {
        return Monos.fromFuture(() -> configClient.createConfig(id, config));
    }

    public Mono<Config> updateConfig(String id, Config config) {
        return Monos.fromFuture(() -> configClient.updateConfig(id, config));
    }

    public Mono<JsValue> updateConfig(String oldId, String id, JsValue config) {
        return Monos.fromFuture(() -> configClient.updateConfig(oldId, id, config));
    }

    public Mono<Done> deleteConfig(String id) {
        return Monos.fromFuture(() -> configClient.deleteConfig(id));
    }

    public Mono<Configs> configs(String pattern) {
        return Monos.fromFuture(() -> configClient.configs(pattern));
    }

    public Mono<Configs> configs(Seq<String> pattern) {
        return Monos.fromFuture(() -> configClient.configs(pattern));
    }

    public Mono<JsValue> config(String key) {
        return Monos.fromFuture(() -> configClient.config(key));
    }

    public Registration onConfigChanged(String key, Consumer<JsValue> cb) {
        return configClient.onConfigChanged(key, cb);
    }

    public Registration onEvent(String pattern, Consumer<ConfigEvent> cb) {
        return configClient.onEvent(pattern, cb);
    }

    public Flux<ConfigEvent> configsStream(String pattern) {
        return Flux.from(configClient.configsStream(pattern));
    }
}
