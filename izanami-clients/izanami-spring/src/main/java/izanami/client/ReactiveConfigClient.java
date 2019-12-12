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

    /**
     * Create a config
     * @param config the config to create
     * @return The config
     */
    public Mono<Config> createConfig(Config config) {
        return Monos.fromFuture(() -> configClient.createConfig(config));
    }

    /**
     * Create a config
     * @param id the id of the config to create
     * @param config a json value of the config
     * @return The config
     */
    public Mono<JsValue> createConfig(String id, JsValue config) {
        return Monos.fromFuture(() -> configClient.createConfig(id, config));
    }

    /**
     * Update a config with an id and a config. If the id has changed, the id in param should be the old value.
     * @param id the new id of the config
     * @param config a config with its id
     * @return The config
     */
    public Mono<Config> updateConfig(String id, Config config) {
        return Monos.fromFuture(() -> configClient.updateConfig(id, config));
    }

    /**
     * Update a config with an id and a Json value
     * There is an oldId and a new id if the id has changed. In the other cases it should be the same value.
     * @param oldId the previous id of the config
     * @param id the new id of the config
     * @param config a json value of the config
     * @return The config
     */
    public Mono<JsValue> updateConfig(String oldId, String id, JsValue config) {
        return Monos.fromFuture(() -> configClient.updateConfig(oldId, id, config));
    }

    /**
     * Delete a config by id.
     * @param id the id of the config to delete
     * @return done
     */
    public Mono<Done> deleteConfig(String id) {
        return Monos.fromFuture(() -> configClient.deleteConfig(id));
    }

    /**
     * Get configs for a pattern like my:keys:*
     * @param pattern the pattern to search
     * @return the configs
     */
    public Mono<Configs> configs(String pattern) {
        return Monos.fromFuture(() -> configClient.configs(pattern));
    }

    /**
     * Get configs for a pattern like my:keys:*
     * @param pattern the pattern to search
     * @return the configs
     */
    public Mono<Configs> configs(Seq<String> pattern) {
        return Monos.fromFuture(() -> configClient.configs(pattern));
    }


    /**
     * Get a config by his key.
     * @param key the key to look up for
     * @return a json value. Empty if the config is not set.
     */
    public Mono<JsValue> config(String key) {
        return Monos.fromFuture(() -> configClient.config(key));
    }

    /**
     * Register a callback to be notified when a config change.
     * @param key
     * @param cb
     * @return the registration
     */
    public Registration onConfigChanged(String key, Consumer<JsValue> cb) {
        return configClient.onConfigChanged(key, cb);
    }

    /**
     * Register a callback to be notified of events concerning configs.
     * @param pattern
     * @param cb
     * @return the registration
     */
    public Registration onEvent(String pattern, Consumer<ConfigEvent> cb) {
        return configClient.onEvent(pattern, cb);
    }

    /**
     *
     * @param pattern
     * @return The events as Flux
     */
    public Flux<ConfigEvent> configsStream(String pattern) {
        return Flux.from(configClient.configsStream(pattern));
    }
}
