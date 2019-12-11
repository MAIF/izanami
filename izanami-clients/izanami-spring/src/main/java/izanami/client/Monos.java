package izanami.client;

import reactor.core.publisher.Mono;
import io.vavr.concurrent.Future;
import java.util.function.Supplier;

import static java.util.function.Function.identity;

class Monos {

    static <T> Mono<T> fromFuture(Supplier<Future<T>> f) {
        return Mono.fromCallable(() ->
            Mono.fromCompletionStage(f.get().toCompletableFuture())
        ).flatMap(identity());
    }

    static <T> Future<T> toFuture(Mono<T> mono) {
        return Future.fromCompletableFuture(mono.toFuture());
    }
}
