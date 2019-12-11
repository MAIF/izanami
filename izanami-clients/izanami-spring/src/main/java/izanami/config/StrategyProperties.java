package izanami.config;

import io.vavr.control.Option;
import izanami.ErrorStrategies;
import izanami.ErrorStrategy;
import izanami.Strategies;
import izanami.Strategy;
import scala.concurrent.duration.FiniteDuration;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.vavr.API.*;
public class StrategyProperties {

    @NotNull
    @Pattern(regexp = "(Crash|RecoverWithFallback)")
    private String errorStrategy = "RecoverWithFallback";
    @NotNull
    @Pattern(regexp = "(DevStrategy|FetchStrategy|FetchWithCacheStrategy|CacheWithSseStrategy|CacheWithPollingStrategy)")
    private String type;
    private Integer maxElement;
    private FiniteDuration duration;
    private FiniteDuration pollingInterval;
    private List<String> patterns;

    Strategy toStrategy() {
        ErrorStrategy errorStrategy = Match(this.errorStrategy).of(
                Case($("Crash"), ErrorStrategies.crash()),
                Case($("RecoverWithFallback"), ErrorStrategies.recoverWithFallback())
        );

        return Match(type).of(
                Case($("DevStrategy"), Strategies::dev),
                Case($("FetchStrategy"), () -> Strategies.fetchStrategy(errorStrategy)),
                Case($("FetchWithCacheStrategy"), () -> Strategies.fetchWithCacheStrategy(
                        Option(maxElement).getOrElse(1000),
                        Option(duration).getOrElse(FiniteDuration.create(1, TimeUnit.MINUTES)),
                        errorStrategy
                )),
                Case($("CacheWithSseStrategy"), () -> {
                    String[] patterns = Option.of(this.patterns)
                            .map(p -> io.vavr.collection.List.ofAll(p).toJavaArray(String[]::new))
                            .getOrElse(new String[]{"*"});
                    return Strategies.smartCacheWithSseStrategy(patterns).withErrorStrategy(errorStrategy);
                }),
                Case($("CacheWithPollingStrategy"), () -> {
                    String[] patterns = Option.of(this.patterns)
                            .map(p -> io.vavr.collection.List.ofAll(p).toJavaArray(String[]::new))
                            .getOrElse(new String[]{"*"});
                    return Strategies.smartCacheWithPollingStrategy(
                            Option(pollingInterval).getOrElse(FiniteDuration.create(1, TimeUnit.MINUTES)),
                            patterns
                    ).withErrorStrategy(errorStrategy);
                })
        );
    }

    public String getErrorStrategy() {
        return errorStrategy;
    }

    public void setErrorStrategy(String errorStrategy) {
        this.errorStrategy = errorStrategy;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getMaxElement() {
        return maxElement;
    }

    public void setMaxElement(Integer maxElement) {
        this.maxElement = maxElement;
    }

    public FiniteDuration getDuration() {
        return duration;
    }

    public void setDuration(FiniteDuration duration) {
        this.duration = duration;
    }

    public FiniteDuration getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(FiniteDuration pollingInterval) {
        this.pollingInterval = pollingInterval;
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public void setPatterns(List<String> patterns) {
        this.patterns = patterns;
    }
}
