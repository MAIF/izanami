package izanami.client;

import io.vavr.collection.List;
import io.vavr.collection.Seq;
import io.vavr.control.Option;
import izanami.ExperimentVariantDisplayed;
import izanami.ExperimentVariantWon;
import izanami.Variant;
import izanami.javadsl.ExperimentClient;
import izanami.javadsl.ExperimentsClient;
import org.reactivecouchbase.json.JsValue;
import reactor.core.publisher.Mono;

public class ReactiveExperimentClient {

    private final ExperimentsClient experimentClient;

    public ReactiveExperimentClient(ExperimentsClient experimentClient) {
        this.experimentClient = experimentClient;
    }

    /**
     * Get an experiment by id.
     * @param id the experiment id
     * @return an optional client if found
     */
    public Mono<Option<ExperimentClient>> experiment(String id) {
        return Monos.fromFuture(() -> experimentClient.experiment(id));
    }

    /**
     * Search experiments by pattern
     *
     * @param pattern the patterns
     * @return The list of found experiments
     */
    public Mono<List<ExperimentClient>> list(Seq<String> pattern) {
        return Monos.fromFuture(() -> experimentClient.list(pattern));
    }

    /**
     * Find the tree of experiments by pattern and resolve the affacted variant
     * @param pattern the patterns ti find
     * @param clientId the client id to find the variant for
     * @return the tree as json
     */
    public Mono<JsValue> tree(String pattern, String clientId) {
        return Monos.fromFuture(() -> experimentClient.tree(pattern, clientId));
    }

    /**
     * Find the tree of experiments by pattern and resolve the affacted variant
     * @param pattern the patterns ti find
     * @param clientId the client id to find the variant for
     * @return the tree as json
     */
    public Mono<JsValue> tree(Seq<String> pattern, String clientId) {
        return Monos.fromFuture(() -> experimentClient.tree(pattern, clientId));
    }

    /**
     * Get the variant for an experiment and a client
     *
     * @param id the experiment id
     * @param clientId the client id to find the variant for
     * @return an optional variant
     */
    public Mono<Option<Variant>> getVariantFor(String id, String clientId) {
        return Monos.fromFuture(() -> experimentClient.getVariantFor(id, clientId));
    }

    /**
     * Mark a variant as displayed
     *
     * @param id the experiment id
     * @param clientId the client id to find the variant for
     * @return
     */
    public Mono<ExperimentVariantDisplayed> markVariantDisplayed(String id, String clientId) {
        return Monos.fromFuture(() -> experimentClient.markVariantDisplayed(id, clientId));
    }

    /**
     * Mark a variant as won
     *
     * @param id the experiment id
     * @param clientId the client id to find the variant for
     * @return
     */
    public Mono<ExperimentVariantWon> markVariantWon(String id, String clientId) {
        return Monos.fromFuture(() -> experimentClient.markVariantWon(id, clientId));
    }
}
