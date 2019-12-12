package izanami.config;

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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(classes = ConfigMissingFeatureClientTest.Application.class)
@ActiveProfiles(profiles = "missingfeature")
public class ConfigMissingFeatureClientTest {

    @Autowired
    IzanamiClient izanamiClient;

    @Autowired
    ConfigClient configClient;

    @Autowired
    ExperimentsClient experimentsClient;

    @Autowired
    ReactiveConfigClient reactiveConfigClient;

    @Autowired
    ReactiveExperimentClient reactiveExperimentClient;

    @Autowired
    IzanamiProperties izanamiProperties;

    @Autowired
    ConfigProperties configProperties;

    @Autowired
    ExperimentProperties experimentProperties;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    Proxy proxy;

    @Test
    public void testConfig() {
        assertThat(izanamiProperties.getHost()).isEqualTo("http://localhost:8080");
        assertThat(izanamiProperties.getClientId()).isEqualTo("xxxx");
        assertThat(izanamiProperties.getClientSecret()).isEqualTo("xxxx");
        assertThat(izanamiProperties.getClientIdHeaderName()).isEqualTo("Izanami-Client-Id");
        assertThat(izanamiProperties.getClientSecretHeaderName()).isEqualTo("Izanami-Client-Secret");
        assertThat(izanamiProperties.getBackend()).isEqualTo("Undefined");
        assertThat(izanamiProperties.getPageSize()).isEqualTo(200);
        assertThat(izanamiProperties.getZoneId()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(izanamiProperties.getDispatcher()).isEqualTo("akka.actor.default-dispatcher");
    }

    @Test
    public void featureProperties() {
        Map<String, FeatureClient> beansOfType = applicationContext.getBeansOfType(FeatureClient.class);
        assertThat(beansOfType).isEmpty();
    }

    @Test
    public void configProperties() {
        assertThat(configProperties.getAutocreate()).isEqualTo(false);
        assertThat(configProperties.getFallback()).isNull();
        assertThat(configProperties.getStrategy().toStrategy()).isEqualTo(Strategies.dev());
    }

    @Test
    public void experimentProperties() {
        assertThat(experimentProperties.getFallback()).isNull();
        assertThat(experimentProperties.getStrategy().toStrategy()).isEqualTo(Strategies.dev());
    }

    @SpringBootApplication
    @Import({AkkaConfig.class, IzanamiConfig.class, IzanamiReactiveConfig.class, IzanamiDestroy.class})
    public static class Application {
        public static void main(String[] args) {
            SpringApplication.run(Application.class, args);
        }
    }
}
