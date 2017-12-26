package izanami.javadsl;

import akka.actor.ActorSystem;
import akka.testkit.javadsl.TestKit;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.typesafe.config.ConfigFactory;
import io.vavr.concurrent.Future;
import io.vavr.control.Option;
import izanami.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import izanami.javadsl.*;
import org.junit.Test;
import org.reactivecouchbase.json.JsObject;
import org.reactivecouchbase.json.JsValue;
import org.reactivecouchbase.json.Json;
import scala.concurrent.duration.FiniteDuration;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import static org.reactivecouchbase.json.Syntax.$;
import static org.assertj.core.api.Assertions.*;
public class IzanamiTest {

    private final static Integer PORT = 8089;

    IzanamiClient izanamiClient;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(PORT); // No-args constructor defaults to port 8080

    private ActorSystem system = ActorSystem.create("izanami-test", ConfigFactory.parseString(
            "izanami-example.blocking-io-dispatcher {\n" +
                "  type = Dispatcher\n" +
                "  executor = \"thread-pool-executor\"\n" +
                "  thread-pool-executor {\n" +
                "    fixed-pool-size = 32\n" +
                "  }\n" +
                "  throughput = 1\n" +
                "}"
    ));

    @Before
    public void initCLient() {
        //#configure-client
        izanamiClient = IzanamiClient.client(
                system,
                ClientConfig.create("http://localhost:8089")
                        .withClientId("xxxx")
                        .withClientIdHeaderName("Another-Client-Id-Header")
                        .withClientSecret("xxxx")
                        .withClientSecretHeaderName("Another-Client-Secret-Header")
                        .sseBackend()
                        .withDispatcher("izanami-example.blocking-io-dispatcher")
                        .withPageSize(50)
                        .withZoneId(ZoneId.of("Europe/Paris"))
        );
        //#configure-client
    }


    public void smartCache() {

        IzanamiClient izanamiClient = IzanamiClient.client(
                system,
                ClientConfig.create("http://localhost:8089")
                        .withClientId("xxxx")
                        .withClientIdHeaderName("Another-Client-Id-Header")
                        .withClientSecret("xxxx")
                        .withClientSecretHeaderName("Another-Client-Secret-Header")
                        .sseBackend()
                        .withDispatcher("izanami-example.blocking-io-dispatcher")
                        .withPageSize(50)
                        .withZoneId(ZoneId.of("Europe/Paris"))
        );

        //#smart-cache
        ConfigClient configClient = izanamiClient.configClient(
                Strategies.smartCacheWithPollingStrategy(
                        FiniteDuration.create(20, TimeUnit.SECONDS),
                        "my:configs:*", "other:pattern"
                ),
                Configs.configs(
                        Config.config("my:config", Json.obj(
                                $("value", "Fallback value")
                        ))
                )
        );
        //#smart-cache

        //#feature-smart-cache
        FeatureClient featureClient = izanamiClient.featureClient(
                Strategies.smartCacheWithSseStrategy(
                "my:features:*", "other:pattern"
                ),
                Features.features(
                        Features.feature("my:feature", false)
                )
        );
        //#feature-smart-cache
    }

    @Test
    public void configs() {

        stubFor(
            get(urlEqualTo("/api/configs/my:config"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withBody(
                        Json.stringify(Json.obj(
                                $("id", "my:config"),
                                $("value", Json.stringify(Json.obj($("value", "A value"))))
                        ))
                )
            )
        );
        stubFor(
            get(urlEqualTo("/api/configs?pattern=my:*&pageSize=50&page=1"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withBody(
                        Json.stringify(
                            Json.obj(
                                $("results", Json.arr(Json.obj(
                                    $("id", "my:config"),
                                    $("value", Json.stringify(Json.obj($("value", "A value"))))
                                ))),
                                $("metadata", Json.obj(
                                    $("page", 1),
                                    $("pageSize", 50),
                                    $("count", 1),
                                    $("nbPages", 1)
                                ))
                            )
                    ))
                )
        );

        //#configure-config-client
        ConfigClient configClient = izanamiClient.configClient(
                Strategies.fetchStrategy(),
                Configs.configs(
                        Config.config("my:config", Json.obj(
                                $("value", "Fallback value")
                        ))
                )
        );
        //#configure-config-client

        //#all-configs
        Future<Configs> futureConfigs = configClient.configs("my:*");
        futureConfigs.onSuccess( (Configs configs) -> {
            JsValue config = configs.config("my:config");
            System.out.println(config.field("value").asString());
        });
        //#all-configs

        //#one-config
        Future<JsValue> futureConfig = configClient.config("my:config");
        futureConfig.onSuccess( (JsValue config) -> {
            System.out.println(config.field("value").asString());
        });
        //#one-config

        assertThat(futureConfig.get().field("value").asString()).isEqualTo("A value");
        verify(getRequestedFor(urlMatching("/api/configs/my:config")));
    }



    @Test
    public void feature() {

        stubFor(
                post(urlEqualTo("/api/features/my:feature/check"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(
                                        Json.stringify(Json.obj(
                                                $("active", true)
                                        ))
                                )
                        )
        );

        //#configure-feature-client
        FeatureClient featureClient = izanamiClient.featureClient(
                Strategies.fetchStrategy(),
                Features.features(
                        Features.feature("my:feature", false)
                )
        );
        //#configure-feature-client

        //#features-check
        Future<Boolean> futureCheck = featureClient.checkFeature("my:feature");
        //#features-check

        //#features-check-context
        Future<Boolean> futureCheckContext = featureClient.checkFeature("my:feature", Json.obj(
                $("context", true)
        ));
        //#features-check-context

        //#features-check-conditional
        Future<String> conditonal = featureClient.featureOrElse("my:feature",
                () -> "Feature is active",
                () -> "Feature is not active"
        );
        //#features-check-conditional

        //#features-check-conditional-context
        Future<String> conditonalContext = featureClient.featureOrElse(
                "my:feature",
                Json.obj($("context", true)),
                () -> "Feature is active",
                () -> "Feature is not active"
        );
        //#features-check-conditional-context

        assertThat(futureCheck.get()).isTrue();
        verify(postRequestedFor(urlMatching("/api/features/my:feature/check")));
        //wireMockRule.findAllUnmatchedRequests().forEach(System.out::println);
    }


    @Test
    public void features() {

        stubFor(
                get(urlPathEqualTo("/api/features"))
                        .withQueryParam("pattern", equalTo("my:feature:*"))
                        .withQueryParam("page", equalTo("1"))
                        .withQueryParam("active", equalTo("true"))
                        .withQueryParam("pageSize", equalTo("50"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(Json.stringify(
                                        Json.obj(
                                                $("results", Json.arr(Json.obj(
                                                        $("id", "my:feature:test"),
                                                        $("activationStrategy", "NO_STRATEGY"),
                                                        $("enabled", true)
                                                ))),
                                                $("metadata", Json.obj(
                                                        $("page", 1),
                                                        $("pageSize", 50),
                                                        $("count", 1),
                                                        $("nbPages", 1)
                                                ))
                                        )
                                ))
                        )
        );

        FeatureClient featureClient = izanamiClient.featureClient(
                Strategies.fetchStrategy(),
                Features.features(
                        Features.feature("my:feature", false)
                )
        );

        //#features-list
        Future<Features> futureFeatures = featureClient.features("my:feature:*");

        futureFeatures.onSuccess(features -> {
            boolean active = features.isActive("my:feature:test");
            if (active) {
                System.out.println("Feature my:feature:test is active");
            } else {
                System.out.println("Feature my:feature:test is active");
            }
            JsObject tree = features.tree();
            System.out.println("Tree is " + Json.prettyPrint(tree));
        });
        //#features-list

        boolean active = futureFeatures.get().isActive("my:feature:test");
        assertThat(active).isTrue();

        verify(getRequestedFor(urlPathEqualTo("/api/features")));
    }

    @Test
    public void experiments() {
        //#experiment-client
        ExperimentsClient experimentsClient = izanamiClient.experimentClient(Strategies.fetchStrategy());
        //#experiment-client

        //#get-variant
        Future<Option<Variant>> futureVariant = experimentsClient.getVariantFor("my:experiment", "clientId");
        futureVariant.onSuccess(mayBeVariant ->
                System.out.println(mayBeVariant)
        );
        //#get-variant

        //#displayed-variant
        Future<ExperimentVariantDisplayed> futureDisplayed = experimentsClient.markVariantDisplayed("my:experiment", "clientId");
        futureDisplayed.onSuccess(event ->
                System.out.println(event)
        );
        //#displayed-variant

        //#won-variant
        Future<ExperimentVariantWon> futureWon = experimentsClient.markVariantWon("my:experiment", "clientId");
        futureWon.onSuccess(event ->
                System.out.println(event)
        );
        //#won-variant
    }


    @After
    public void stopAll() {
        TestKit.shutdownActorSystem(system);
    }

}
