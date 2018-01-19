package izanami.example;


import akka.actor.ActorSystem;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.jackson.datatype.VavrModule;
import izanami.ClientConfig;
import izanami.Experiments;
import izanami.Strategies;
import izanami.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;


@SpringBootApplication
public class Application {

    static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

    @Autowired
    Environment environment;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    ActorSystem actorSystem() {
        return ActorSystem.create();
    }

    @Bean
    @Autowired
    RestTemplate restTemplate(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        return builder.build();
    }

    @Bean
    Module vavrModule() {
        return new VavrModule();
    }

    @Bean
    @Autowired
    IzanamiClient izanamiClient(ActorSystem actorSystem) {
        String host = environment.getProperty("izanami.host");
        String clientId = environment.getProperty("izanami.clientId");
        String clientSecret = environment.getProperty("izanami.clientSecret");
        LOGGER.info("Creating izanami client with host {}, client id {}", host, clientId);
        return IzanamiClient.client(
                    actorSystem,
                    ClientConfig
                        .create(host)
                        .withClientId(clientId)
                        .withClientSecret(clientSecret)
                        .withClientIdHeaderName("Izanami-Client-Id")
                        .withClientSecretHeaderName("Izanami-Client-Secret")
                        .withDispatcher("izanami-example.blocking-io-dispatcher")
                        .sseBackend()
                );
    }

    @Bean
    @Autowired
    Proxy proxy(IzanamiClient izanamiClient, FeatureClient featureClient, ExperimentsClient experimentClient) {
        return izanamiClient.proxy()
                .withFeaturePattern("mytvshows:*")
                .withFeatureClient(featureClient)
                .withExperimentPattern("mytvshows:*")
                .withExperimentsClient(experimentClient);
    }

    @Configuration
    @Profile("izanamiProd")
    static class Prod {

        @Bean
        @Autowired
        FeatureClient featureClient(IzanamiClient izanamiClient, Environment environment) {
            return izanamiClient.featureClient(
                    Strategies.smartCacheWithSseStrategy("mytvshows:*"),
                    Features.parseJson(environment.getProperty("izanami.fallback.features"))
            );
        }

        @Bean
        @Autowired
        ConfigClient configClient(IzanamiClient izanamiClient, Environment environment) {
            return izanamiClient.configClient(
                    Strategies.smartCacheWithSseStrategy("mytvshows:*"),
                    Configs.parseJson(environment.getProperty("izanami.fallback.configs"))
            );
        }

        @Bean
        @Autowired
        ExperimentsClient experimentClient(IzanamiClient izanamiClient, Environment environment) {

            return izanamiClient.experimentClient(
                    Strategies.fetchStrategy(),
                    Experiments.parseJson(environment.getProperty("izanami.fallback.experiments"))
            );
        }


    }

    @Configuration
    @Profile("izanamiDev")
    static class Dev {

        @Bean
        @Autowired
        FeatureClient featureClientDev(IzanamiClient izanamiClient, Environment environment) {
            String json = environment.getProperty("izanami.fallback.features");
            LOGGER.info("Loading feature fallback \n{}", json);
            return izanamiClient.featureClient(
                    Strategies.dev(),
                    Features.parseJson(json)
            );
        }

        @Bean
        @Autowired
        ConfigClient configClientDev(IzanamiClient izanamiClient, Environment environment) {
            String json = environment.getProperty("izanami.fallback.configs");
            LOGGER.info("Loading configs fallback \n{}", json);
            return izanamiClient.configClient(
                    Strategies.dev(),
                    Configs.parseJson(json)
            );
        }

        @Bean
        @Autowired
        ExperimentsClient experimentClient(IzanamiClient izanamiClient, Environment environment) {
            String json = environment.getProperty("izanami.fallback.experiments");
            LOGGER.info("Loading configs fallback \n{}", json);
            return izanamiClient.experimentClient(
                    Strategies.dev(),
                    Experiments.parseJson(json)
            );
        }

    }

}
