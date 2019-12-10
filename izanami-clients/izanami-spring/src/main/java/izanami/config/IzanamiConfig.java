package izanami.config;


import akka.actor.ActorSystem;
import izanami.ClientConfig;
import izanami.Experiments;
import izanami.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static io.vavr.API.*;

@Configuration
public class IzanamiConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(IzanamiConfig.class);

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

}
