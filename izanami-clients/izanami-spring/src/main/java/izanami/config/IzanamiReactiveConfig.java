package izanami.config;


import izanami.client.ReactiveConfigClient;
import izanami.client.ReactiveExperimentClient;
import izanami.client.ReactiveFeatureClient;
import izanami.javadsl.ConfigClient;
import izanami.javadsl.ExperimentsClient;
import izanami.javadsl.FeatureClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@ConditionalOnClass(Mono.class)
@Configuration
public class IzanamiReactiveConfig {

    @Bean
    @ConditionalOnBean(ConfigClient.class)
    ReactiveConfigClient reactiveConfigClient(ConfigClient client) {
        return new ReactiveConfigClient(client);
    }

    @Bean
    @ConditionalOnBean(FeatureClient.class)
    ReactiveFeatureClient reactiveFeatureClient(FeatureClient client) {
        return new ReactiveFeatureClient(client);
    }

    @Bean
    @ConditionalOnBean(ExperimentsClient.class)
    ReactiveExperimentClient reactiveExperimentClient(ExperimentsClient experimentsClient) {
        return new ReactiveExperimentClient(experimentsClient);
    }
}
