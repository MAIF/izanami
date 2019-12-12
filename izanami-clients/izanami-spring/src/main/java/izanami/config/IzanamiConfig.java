package izanami.config;


import akka.actor.ActorSystem;
import io.vavr.collection.List;
import io.vavr.control.Option;
import izanami.ClientConfig;
import izanami.Experiments;
import izanami.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.function.BiFunction;

import static io.vavr.API.*;

@Configuration
public class IzanamiConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(IzanamiConfig.class);

    @Bean
    FiniteDurationConverter finiteDurationConverter() {
        return new FiniteDurationConverter();
    }

    @Bean
    IzanamiClient izanamiClient(ActorSystem actorSystem, IzanamiProperties izanamiProperties) {

        ClientConfig tmpClientConfig = ClientConfig.create(izanamiProperties.getHost())
                .withClientId(izanamiProperties.getClientId())
                .withClientSecret(izanamiProperties.getClientSecret())
                .withClientIdHeaderName(izanamiProperties.getClientIdHeaderName())
                .withClientSecretHeaderName(izanamiProperties.getClientSecretHeaderName())
                .withPageSize(izanamiProperties.getPageSize())
                .withZoneId(izanamiProperties.getZoneId())
                .withDispatcher(izanamiProperties.getDispatcher());

        ClientConfig clientConfig = Match(izanamiProperties.getBackend()).of(
            Case($("SseBackend"), tmpClientConfig.sseBackend()),
            Case($("PollingBackend"), tmpClientConfig.pollingBackend()),
            Case($("Undefined"), tmpClientConfig.undefined())
        );

        LOGGER.debug("Creating izanami client with configuration {}", clientConfig);

        return IzanamiClient.client(actorSystem, clientConfig);
    }

    @Bean
    @ConditionalOnProperty(prefix = "izanami.feature", name = "strategy.type")
    FeatureClient featureClient(IzanamiClient izanamiClient, FeatureProperties featureProperties) {
        LOGGER.debug("Creating izanami feature client with configuration {}", featureProperties);
        return izanamiClient.featureClient(
                featureProperties.getStrategy().toStrategy(),
                Option(featureProperties.getFallback()).filter(s -> !s.isEmpty()).map(Features::parseJson).getOrElse(Features.empty()),
                featureProperties.getAutocreate()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "izanami.config", name = "strategy.type")
    ConfigClient configClient(IzanamiClient izanamiClient, ConfigProperties configProperties) {
        LOGGER.debug("Creating izanami config client with configuration {}", configProperties);
        return izanamiClient.configClient(
                configProperties.getStrategy().toStrategy(),
                Option(configProperties.getFallback()).filter(s -> !s.isEmpty()).map(Configs::parseJson).getOrElse(Configs.configs()),
                configProperties.getAutocreate()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "izanami.experiment", name = "strategy.type")
    ExperimentsClient experimentClient(IzanamiClient izanamiClient, ExperimentProperties experimentProperties) {
        LOGGER.debug("Creating izanami experiment client with configuration {}", experimentProperties);
        return izanamiClient.experimentClient(
            experimentProperties.getStrategy().toStrategy(),
            Option(experimentProperties.getFallback()).filter(s -> !s.isEmpty()).map(Experiments::parseJson).getOrElse(Experiments.create())
        );
    }

    @Bean
    @ConditionalOnBean(IzanamiClient.class)
    Proxy proxy(IzanamiClient izanamiClient,
                ProxyProperties proxyProperties,
                Optional<FeatureClient> featureClient,
                Optional<ConfigClient> configClient,
                Optional<ExperimentsClient> experimentsClient) {

        Proxy basedProxy = izanamiClient.proxy();

        Proxy featureProxy = zip(
                Option.ofOptional(featureClient),
                Option(proxyProperties.getFeature()).flatMap(f -> Option(f.getPatterns())),
                (c, p) -> basedProxy
                        .withFeatureClient(c)
                        .withFeaturePattern(List.ofAll(p).toJavaArray(String[]::new))
        ).getOrElse(basedProxy);

        Proxy configProxy = zip(
                Option.ofOptional(configClient),
                Option(proxyProperties.getConfig()).flatMap(f -> Option(f.getPatterns())),
                (c, p) -> featureProxy
                        .withConfigClient(c)
                        .withConfigPattern(List.ofAll(p).toJavaArray(String[]::new))
        ).getOrElse(featureProxy);

        return zip(
                Option.ofOptional(experimentsClient),
                Option(proxyProperties.getExperiment()).flatMap(f -> Option(f.getPatterns())),
                (c, p) -> configProxy
                        .withExperimentsClient(c)
                        .withExperimentPattern(List.ofAll(p).toJavaArray(String[]::new))
        ).getOrElse(configProxy);
    }

    static <T1, T2, T> Option<T> zip(Option<T1> t1, Option<T2> t2, BiFunction<T1, T2, T> f) {
        return t1.flatMap(v1 ->
            t2.map(v2 -> f.apply(v1, v2))
        );
    }
}
