package izanami.client;

import io.vavr.collection.List;
import io.vavr.collection.Seq;
import io.vavr.concurrent.Future;
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

    public Mono<Option<ExperimentClient>> experiment(String id) {
        return Monos.fromFuture(() -> experimentClient.experiment(id));
    }

    public Mono<List<ExperimentClient>> list(Seq<String> pattern) {
        return Monos.fromFuture(() -> experimentClient.list(pattern));
    }

    public Mono<JsValue> tree(String pattern, String clientId) {
        return Monos.fromFuture(() -> experimentClient.tree(pattern, clientId));
    }

    public Mono<JsValue> tree(Seq<String> pattern, String clientId) {
        return Monos.fromFuture(() -> experimentClient.tree(pattern, clientId));
    }

    public Mono<Option<Variant>> getVariantFor(String id, String clientId) {
        return Monos.fromFuture(() -> experimentClient.getVariantFor(id, clientId));
    }

    public Mono<ExperimentVariantDisplayed> markVariantDisplayed(String id, String clientId) {
        return Monos.fromFuture(() -> experimentClient.markVariantDisplayed(id, clientId));
    }

    public Mono<ExperimentVariantWon> markVariantWon(String id, String clientId) {
        return Monos.fromFuture(() -> experimentClient.markVariantWon(id, clientId));
    }
}
