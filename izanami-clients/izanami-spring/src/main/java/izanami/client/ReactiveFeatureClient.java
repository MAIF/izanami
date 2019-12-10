package izanami.client;

import akka.Done;
import io.vavr.Function0;
import io.vavr.collection.Seq;
import io.vavr.control.Option;
import izanami.Feature;
import izanami.FeatureEvent;
import izanami.FeatureType;
import izanami.javadsl.FeatureClient;
import izanami.javadsl.Features;
import izanami.javadsl.Registration;
import org.reactivecouchbase.json.JsObject;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

public class ReactiveFeatureClient {

    private final FeatureClient client;

    public ReactiveFeatureClient(FeatureClient client) {
        this.client = client;
    }

    /**
     * Create a feature
     * @param id Feature Id
     * @param enabled If this feature is enabled by default or not
     * @param activationStrategy activationStrategy for this feature (@see {@link izanami.FeatureType})
     * @param parameters optional parameters (depends on activationStrategy)
     * @return
     */
    public Mono<Feature> createJsonFeature(String id, boolean enabled, FeatureType activationStrategy, Option<JsObject> parameters) {
        return Monos.fromFuture(() -> client.createJsonFeature(id, enabled, activationStrategy, parameters));
    }

    public Mono<Feature> createFeature(Feature feature) {
        return Monos.fromFuture(() -> client.createFeature(feature));
    }

    public Mono<Feature> updateFeature(String id, Feature feature) {
        return Monos.fromFuture(() -> client.updateFeature(id, feature));
    }

    public Mono<Feature> switchFeature(String id, boolean enabled) {
        return Monos.fromFuture(() -> client.switchFeature(id, enabled));
    }

    public Mono<Done> deleteFeature(String id) {
        return Monos.fromFuture(() -> client.deleteFeature(id));
    }

    public Mono<Features> features(String pattern) {
        return Monos.fromFuture(() -> client.features(pattern));
    }

    public Mono<Features> features(Seq<String> pattern) {
        return Monos.fromFuture(() -> client.features(pattern));
    }

    public Mono<Features> features(String pattern, JsObject context) {
        return Monos.fromFuture(() -> client.features(pattern, context));
    }

    public Mono<Features> features(Seq<String> pattern, JsObject context) {
        return Monos.fromFuture(() -> client.features(pattern, context));
    }

    public <T> Mono<T> featureOrElse(String key, Function0<T> ok, Function0<T> ko) {
        return Monos.fromFuture(() -> client.featureOrElse(key, ok, ko));
    }

    public <T> Mono<T> featureOrElseAsync(String key, Function0<Mono<T>> ok, Function0<Mono<T>> ko) {
        return Monos.fromFuture(() -> client.featureOrElseAsync(key, () -> Monos.toFuture(ok.apply()), () -> Monos.toFuture(ko.apply())));
    }

    public <T> Mono<T> featureOrElse(String key, JsObject context, Function0<T> ok, Function0<T> ko) {
        return Monos.fromFuture(() -> client.featureOrElse(key, context, ok, ko));
    }

    public <T> Mono<T> featureOrElseAsync(String key, JsObject context, Function0<Mono<T>> ok, Function0<Mono<T>> ko) {
        return Monos.fromFuture(() -> client.featureOrElseAsync(key, context, () -> Monos.toFuture(ok.apply()), () -> Monos.toFuture(ko.apply())));
    }

    public Mono<Boolean> checkFeature(String key) {
        return Monos.fromFuture(() -> client.checkFeature(key));
    }

    public Mono<Boolean> checkFeature(String key, JsObject context) {
        return Monos.fromFuture(() -> client.checkFeature(key, context));
    }

    public Registration onFeatureChanged(String key, Consumer<Feature> cb) {
        return client.onFeatureChanged(key, cb);
    }

    public Registration onEvent(String pattern, Consumer<FeatureEvent> cb) {
        return client.onEvent(pattern, cb);
    }

    public Flux<FeatureEvent> featuresStream(String pattern) {
        return Flux.from(client.featuresStream(pattern));
    }
}
