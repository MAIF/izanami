package izanami.config;

import izanami.ErrorStrategies;
import izanami.Strategies;
import izanami.client.ReactiveConfigClient;
import izanami.client.ReactiveExperimentClient;
import izanami.client.ReactiveFeatureClient;
import izanami.javadsl.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import scala.concurrent.duration.FiniteDuration;

import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(classes = ConfigTest.Application.class)
@ActiveProfiles(profiles = "allvalues")
public class ConfigTest {

    @Autowired
    IzanamiClient izanamiClient;

    @Autowired
    FeatureClient featureClient;

    @Autowired
    ConfigClient configClient;

    @Autowired
    ExperimentsClient experimentsClient;

    @Autowired
    ReactiveConfigClient reactiveConfigClient;

    @Autowired
    ReactiveFeatureClient reactiveFeatureClient;

    @Autowired
    ReactiveExperimentClient reactiveExperimentClient;

    @Autowired
    IzanamiProperties izanamiProperties;

    @Autowired
    FeatureProperties featureProperties;

    @Autowired
    ConfigProperties configProperties;

    @Autowired
    ExperimentProperties experimentProperties;

    @Autowired
    ProxyProperties proxyProperties;

    @Autowired
    Proxy proxy;

    @Test
    public void clientProperties() {
        assertThat(izanamiProperties.getHost()).isEqualTo("http://localhost:8080");
        assertThat(izanamiProperties.getClientId()).isEqualTo("xxxx");
        assertThat(izanamiProperties.getClientSecret()).isEqualTo("xxxx");
        assertThat(izanamiProperties.getClientIdHeaderName()).isEqualTo("Izanami-Client");
        assertThat(izanamiProperties.getClientSecretHeaderName()).isEqualTo("Izanami-Secret");
        assertThat(izanamiProperties.getBackend()).isEqualTo("SseBackend");
        assertThat(izanamiProperties.getPageSize()).isEqualTo(5);
        assertThat(izanamiProperties.getZoneId()).isEqualTo(ZoneId.of("America/Phoenix"));
        assertThat(izanamiProperties.getDispatcher()).isEqualTo("izanami.blocking-dispatcher");
    }

    @Test
    public void featureProperties() {
        assertThat(featureProperties.getAutocreate()).isEqualTo(true);
        assertThat(featureProperties.getFallback()).isEqualTo("" +
                "[\n" +
                "  { \"id\": \"mytvshows:season:markaswatched\", \"enabled\": false },\n" +
                "  { \"id\": \"mytvshows:providers:tvdb\", \"enabled\": true },\n" +
                "  { \"id\": \"mytvshows:providers:betaserie\", \"enabled\": false },\n" +
                "  { \"id\": \"mytvshows:providers:omdb\", \"enabled\": false }\n" +
                "]\n" +
                "");
        assertThat(featureProperties.getStrategy().toStrategy()).isEqualTo(
                Strategies.fetchWithCacheStrategy(5, FiniteDuration.create(2, TimeUnit.MINUTES), ErrorStrategies.recoverWithFallback())
        );
    }

    @Test
    public void configProperties() {
        assertThat(configProperties.getAutocreate()).isEqualTo(true);
        assertThat(configProperties.getFallback()).isEqualTo("" +
                "[\n" +
                "  { \"id\": \"izanami:example:config\", \"value\": { \"emailProvider\": \"test\" } }\n" +
                "]\n");
        assertThat(configProperties.getStrategy().toStrategy()).isEqualTo(
                Strategies.smartCacheWithSseStrategy("mytvshows:*", "izanami:*")
                        .withPollingInterval(FiniteDuration.create(1, TimeUnit.MINUTES))
                        .withErrorStrategy(ErrorStrategies.crash())
        );
    }

    @Test
    public void experimentProperties() {
        assertThat(experimentProperties.getFallback()).isEqualTo("" +
                "[\n" +
                "  {\n" +
                "    \"id\": \"mytvshows:gotoepisodes:button\",\n" +
                "    \"name\": \"Test button\",\n" +
                "    \"description\": \"Test button\",\n" +
                "    \"enabled\": true,\n" +
                "    \"variant\": {\n" +
                "      \"id\": \"A\",\n" +
                "      \"name\": \"Variant A\",\n" +
                "      \"description\": \"Variant A\"\n" +
                "    }\n" +
                "  }\n" +
                "]\n");
        assertThat(experimentProperties.getStrategy().toStrategy()).isEqualTo(Strategies.dev());
    }

    @Test
    public void proxyProperties() {
        assertThat(proxyProperties.getFeature().getPatterns()).contains("feature*");
        assertThat(proxyProperties.getConfig().getPatterns()).contains("config*");
        assertThat(proxyProperties.getExperiment().getPatterns()).contains("experiment*");
    }

    @SpringBootApplication
    @Import({AkkaConfig.class, IzanamiConfig.class, IzanamiReactiveConfig.class, IzanamiDestroy.class})
    public static class Application {

        public static void main(String[] args) {
            SpringApplication.run(Application.class, args);
        }
    }
}
