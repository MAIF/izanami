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
     *
     * @param id Feature Id
     * @param enabled If this feature is enabled by default or not
     * @param activationStrategy activationStrategy for this feature (@see {@link izanami.FeatureType})
     * @param parameters optional parameters (depends on activationStrategy)
     * @return the feature
     */
    public Mono<Feature> createJsonFeature(String id, boolean enabled, FeatureType activationStrategy, Option<JsObject> parameters) {
        return Monos.fromFuture(() -> client.createJsonFeature(id, enabled, activationStrategy, parameters));
    }

    /**
     * Create a feature
     *
     * @param feature Feature
     * @return the feature
     */
    public Mono<Feature> createFeature(Feature feature) {
        return Monos.fromFuture(() -> client.createFeature(feature));
    }

    /**
     * Update a feature
     * @param id the previous id of the feature
     * @param feature the feature to update
     * @return the feature
     */
    public Mono<Feature> updateFeature(String id, Feature feature) {
        return Monos.fromFuture(() -> client.updateFeature(id, feature));
    }

    /**
     * Enabled or disable a feature
     * @param id the id of the feature
     * @param enabled the status to set
     * @return the feature
     */
    public Mono<Feature> switchFeature(String id, boolean enabled) {
        return Monos.fromFuture(() -> client.switchFeature(id, enabled));
    }


    /**
     * Delete a feature
     * @param id the id of the feature to delete
     * @return done
     */
    public Mono<Done> deleteFeature(String id) {
        return Monos.fromFuture(() -> client.deleteFeature(id));
    }


    /**
     * Get features by pattern like my:keys:*
     * @param pattern the pattern to find
     * @return features
     */
    public Mono<Features> features(String pattern) {
        return Monos.fromFuture(() -> client.features(pattern));
    }

    /**
     * Get features by pattern like my:keys:*
     * @param pattern the pattern to find
     * @return features
     */
    public Mono<Features> features(Seq<String> pattern) {
        return Monos.fromFuture(() -> client.features(pattern));
    }

    /**
     * Get features by pattern like my:keys:* with a json context
     * @param pattern the pattern to find
     * @param context the context to evaluate the features
     * @return the features
     */
    public Mono<Features> features(String pattern, JsObject context) {
        return Monos.fromFuture(() -> client.features(pattern, context));
    }

    /**
     * Get features by pattern like my:keys:* with a json context
     * @param pattern the pattern to find
     * @param context the context to evaluate the features
     * @return the features
     */
    public Mono<Features> features(Seq<String> pattern, JsObject context) {
        return Monos.fromFuture(() -> client.features(pattern, context));
    }

    /**
     * Return a value if the feature is active or else a default.
     * @param key the feature key
     * @param ok call if enabled
     * @param ko call if disabled
     * @return the value
     */
    public <T> Mono<T> featureOrElse(String key, Function0<T> ok, Function0<T> ko) {
        return Monos.fromFuture(() -> client.featureOrElse(key, ok, ko));
    }

    /**
     * Return a value if the feature is active or else a default.
     * @param key the feature key
     * @param ok call if enabled
     * @param ko call if disabled
     * @return the value
     */
    public <T> Mono<T> featureOrElseAsync(String key, Function0<Mono<T>> ok, Function0<Mono<T>> ko) {
        return Monos.fromFuture(() -> client.featureOrElseAsync(key, () -> Monos.toFuture(ok.apply()), () -> Monos.toFuture(ko.apply())));
    }

    /**
     * Return a value if the feature is active or else a default.
     * @param key the feature key
     * @param ok call if enabled
     * @param ko call if disabled
     * @return the value
     */
    public <T> Mono<T> featureOrElse(String key, JsObject context, Function0<T> ok, Function0<T> ko) {
        return Monos.fromFuture(() -> client.featureOrElse(key, context, ok, ko));
    }


    /**
     * Return a value if the feature is active or else a default.
     * @param key the feature key
     * @param ok call if enabled
     * @param ko call if disabled
     * @return the value
     */
    public <T> Mono<T> featureOrElseAsync(String key, JsObject context, Function0<Mono<T>> ok, Function0<Mono<T>> ko) {
        return Monos.fromFuture(() -> client.featureOrElseAsync(key, context, () -> Monos.toFuture(ok.apply()), () -> Monos.toFuture(ko.apply())));
    }


    /**
     * Check if a feature is active or not.
     *
     * @param key the feature key
     * @return true if enabled
     */
    public Mono<Boolean> checkFeature(String key) {
        return Monos.fromFuture(() -> client.checkFeature(key));
    }

    /**
     * Check if a feature is active or not.
     *
     * @param key the feature key
     * @param context the context to evaluate the feature
     * @return true if enabled
     */
    public Mono<Boolean> checkFeature(String key, JsObject context) {
        return Monos.fromFuture(() -> client.checkFeature(key, context));
    }
    /**
     * Register a callback to be notified when a feature change.
     *
     * @param key the id of the feature
     * @param cb a function called for each changed feature
     * @return the registration
     */
    public Registration onFeatureChanged(String key, Consumer<Feature> cb) {
        return client.onFeatureChanged(key, cb);
    }

    /**
     * Register a callback to be notified when a some features change.
     *
     * @param pattern the pattern to filter features
     * @param cb a function called for each event
     * @return the registration
     */
    public Registration onEvent(String pattern, Consumer<FeatureEvent> cb) {
        return client.onEvent(pattern, cb);
    }

    /**
     * A stream of events
     * @param pattern the pattern to filter
     * @return the flux
     */
    public Flux<FeatureEvent> featuresStream(String pattern) {
        return Flux.from(client.featuresStream(pattern));
    }
}
