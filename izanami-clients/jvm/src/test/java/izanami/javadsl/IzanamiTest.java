package izanami.javadsl;

import akka.actor.ActorSystem;
import akka.testkit.javadsl.TestKit;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.typesafe.config.ConfigFactory;
import io.vavr.Tuple2;
import io.vavr.concurrent.Future;
import io.vavr.control.Option;
import izanami.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.reactivecouchbase.json.JsObject;
import org.reactivecouchbase.json.JsValue;
import org.reactivecouchbase.json.Json;
import org.reactivecouchbase.json.Syntax;
import scala.concurrent.duration.FiniteDuration;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.vavr.API.*;
import static io.vavr.Patterns.$None;
import static io.vavr.Patterns.$Some;
import static org.assertj.core.api.Assertions.assertThat;

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
                                Syntax.$("value", "Fallback value")
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
                                Syntax.$("id", "my:config"),
                                Syntax.$("value", Json.stringify(Json.obj(Syntax.$("value", "A value"))))
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
                                Syntax.$("results", Json.arr(Json.obj(
                                    Syntax.$("id", "my:config"),
                                    Syntax.$("value", Json.stringify(Json.obj(Syntax.$("value", "A value"))))
                                ))),
                                Syntax.$("metadata", Json.obj(
                                    Syntax.$("page", 1),
                                    Syntax.$("pageSize", 50),
                                    Syntax.$("count", 1),
                                    Syntax.$("nbPages", 1)
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
                                Syntax.$("value", "Fallback value")
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
                                                Syntax.$("active", true)
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
                Syntax.$("context", true)
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
                Json.obj(Syntax.$("context", true)),
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
                                                Syntax.$("results", Json.arr(Json.obj(
                                                        Syntax.$("id", "my:feature:test"),
                                                        Syntax.$("activationStrategy", "NO_STRATEGY"),
                                                        Syntax.$("enabled", true)
                                                ))),
                                                Syntax.$("metadata", Json.obj(
                                                        Syntax.$("page", 1),
                                                        Syntax.$("pageSize", 50),
                                                        Syntax.$("count", 1),
                                                        Syntax.$("nbPages", 1)
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

        JsObject jsonVariant = Json.obj(
                Syntax.$("id", "A"),
                Syntax.$("name", "Variant A"),
                Syntax.$("description", ""),
                Syntax.$("traffic", 0)
        );
        JsObject jsonVariantB = Json.obj(
                Syntax.$("id", "B"),
                Syntax.$("name", "Variant B"),
                Syntax.$("description", ""),
                Syntax.$("traffic", 0)
        );

        JsObject jsonExperiment = Json.obj(
                Syntax.$("id", "my:experiment"),
                Syntax.$("name", "Experiment"),
                Syntax.$("description", ""),
                Syntax.$("enabled", true),
                Syntax.$("variants", Json.arr(jsonVariant, jsonVariantB))
        );

        stubFor(
                get(urlPathEqualTo("/api/tree/experiments"))
                        .withQueryParam("pattern", equalTo("*"))
                        .withQueryParam("clientId", equalTo("clientId"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(Json.stringify(
                                        Json.obj(
                                                Syntax.$("my", Json.obj(
                                                        Syntax.$("experiment", Json.obj(
                                                                Syntax.$("variant", "A")
                                                        ))
                                                ))
                                        )
                                ))
                        )
        );
        stubFor(
                get(urlPathEqualTo("/api/experiments/my:experiment"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(Json.stringify(
                                        jsonExperiment
                                ))
                        )
        );
        stubFor(
                get(urlPathEqualTo("/api/experiments/my:experiment/variant"))
                        .withQueryParam("clientId", equalTo("clientId"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(Json.stringify(
                                        jsonVariant
                                ))
                        )
        );

        stubFor(
                post(urlPathEqualTo("/api/experiments/my:experiment/displayed"))
                        .withQueryParam("clientId", equalTo("clientId"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(Json.stringify(
                                        Json.obj(
                                            Syntax.$("id", "my:experiment:clientId:1"),
                                            Syntax.$("experimentId", "my:experiment"),
                                            Syntax.$("clientId", "clientId"),
                                            Syntax.$("date", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.of(2017, 12, 27, 9, 41, 50))),
                                            Syntax.$("variantId", "A"),
                                            Syntax.$("variant", jsonVariant),
                                            Syntax.$("transformation", 0)
                                        )
                                ))
                        )
        );

        stubFor(
                post(urlPathEqualTo("/api/experiments/my:experiment/won"))
                        .withQueryParam("clientId", equalTo("clientId"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(Json.stringify(
                                        Json.obj(
                                            Syntax.$("id", "my:experiment:clientId:1"),
                                            Syntax.$("experimentId", "my:experiment"),
                                            Syntax.$("clientId", "clientId"),
                                            Syntax.$("date", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.of(2017, 12, 27, 9, 41, 50))),
                                            Syntax.$("variantId", "A"),
                                            Syntax.$("variant", jsonVariant),
                                            Syntax.$("transformation", 0)
                                        )
                                ))
                        )
        );

        //#get-experiment
        Future<Option<ExperimentClient>> futureExperiment = experimentsClient.experiment("my:experiment");
        futureExperiment.onSuccess(mayBeExperiment ->
            Match(mayBeExperiment).of(
                    Case($Some($()), exist -> {
                        String phrase = "Experiment is " + exist;
                        System.out.println(phrase);
                        return phrase;
                    }),
                    Case($None(), __ -> {
                        String phrase = "Experiment not found";
                        System.out.println(phrase);
                        return phrase;
                    })
            )
        );
        //#get-experiment
        Option<ExperimentClient> mayExperiment = futureExperiment.get();
        assertThat(mayExperiment).isNotEmpty();

        //#an-experiment
        ExperimentClient experiment = mayExperiment.get();
        Future<Option<Variant>> clientId = experiment.getVariantFor("clientId");
        Future<ExperimentVariantDisplayed> displayed = experiment.markVariantDisplayed("clientId");
        Future<ExperimentVariantWon> won = experiment.markVariantWon("clientId");
        //#an-experiment

        //#get-variant
        Future<Option<Variant>> futureVariant = experimentsClient.getVariantFor("my:experiment", "clientId");
        futureVariant.onSuccess(mayBeVariant ->
                Match(mayBeVariant).of(
                        Case($Some($()), exist -> {
                            String phrase = "Variant is " + exist;
                            System.out.println(phrase);
                            return phrase;
                        }),
                        Case($None(), __ -> {
                            String phrase = "Variant not found";
                            System.out.println(phrase);
                            return phrase;
                        })
                )
        );
        //#get-variant

        Option<Variant> variants = futureVariant.get();
        assertThat(variants).isNotEmpty();

        //#displayed-variant
        Future<ExperimentVariantDisplayed> futureDisplayed = experimentsClient.markVariantDisplayed("my:experiment", "clientId");
        futureDisplayed.onSuccess(event ->
                System.out.println(event)
        );
        //#displayed-variant
        ExperimentVariantDisplayed experimentVariantDisplayed = futureDisplayed.get();
        assertThat(experimentVariantDisplayed.clientId()).isEqualTo("clientId");
        assertThat(experimentVariantDisplayed.variantId()).isEqualTo("A");


        //#won-variant
        Future<ExperimentVariantWon> futureWon = experimentsClient.markVariantWon("my:experiment", "clientId");
        futureWon.onSuccess(event ->
                System.out.println(event)
        );
        //#won-variant
        ExperimentVariantWon experimentVariantWon = futureWon.get();
        assertThat(experimentVariantWon.clientId()).isEqualTo("clientId");
        assertThat(experimentVariantWon.variantId()).isEqualTo("A");

        //#experiment-tree
        Future<JsValue> futureTree = experimentsClient.tree("*", "clientId");
        futureTree.onSuccess(tree -> {
            assertThat(tree).isEqualTo(
                    Json.obj(
                            Syntax.$("my", Json.obj(
                                    Syntax.$("experiment", Json.obj(
                                            Syntax.$("variant", "A")
                                    ))
                            ))
                    )
            );
        });
        //#experiment-tree
    }

    @Test
    public void proxy() {
        //#proxy
        ConfigClient configClient = izanamiClient.configClient(
                Strategies.dev(),
                Configs.configs(
                        Config.config("configs:test", Json.obj(
                                Syntax.$("value", 2)
                        ))
                )
        );
        FeatureClient featureClient = izanamiClient.featureClient(
                Strategies.dev(),
                Features.features(
                        Features.feature("features:test1", true)
                )
        );
        ExperimentsClient experimentsClient = izanamiClient.experimentClient(
                Strategies.dev(),
                Experiments.create(
                        ExperimentFallback.create(
                                "experiments:id",
                                "Experiment",
                                "An Experiment",
                                true,
                                Variant.create("A", "Variant A", "Variant A")
                        )));

        Proxy proxy = izanamiClient.proxy()
                .withConfigClient(configClient)
                .withConfigPattern("configs:*")
                .withFeatureClient(featureClient)
                .withFeaturePattern("features:*")
                .withExperimentsClient(experimentsClient)
                .withExperimentPattern("experiments:*");

        Future<Tuple2<Integer, JsValue>> fJsonResponse = proxy.statusAndJsonResponse();
        fJsonResponse.onSuccess(t ->
                System.out.println("Code = "+t._1+", json body = "+t._2)
        );

        //Or with string response and additional infos :
        Future<Tuple2<Integer, String>> fStringResponse = proxy.statusAndStringResponse(Json.obj().with("id", "ragnard.lodbrock@gmail.com"), "ragnard.lodbrock@gmail.com");
        fStringResponse.onSuccess(t ->
                System.out.println("Code = "+t._1+", string body = "+t._2)
        );
        // Experiment proxy

        Future<Tuple2<Integer, JsValue>> markVariantDisplayed = proxy.markVariantDisplayed("experiments:id", "ragnars.lodbrock@gmail.com");
        Future<Tuple2<Integer, JsValue>> markVariantWon = proxy.markVariantWon("experiments:id", "ragnars.lodbrock@gmail.com");

        //#proxy

        Tuple2<Integer, JsValue> resp = fJsonResponse.get();
        assertThat(resp._1).isEqualTo(200);
        assertThat(resp._2).isEqualTo(Json.parse("{\"features\":{\"features\":{\"test1\":{\"active\":true}}},\"configurations\":{\"configs\":{\"test\":{\"value\":2}}},\"experiments\":{}}"));

        Tuple2<Integer, String> stringResp = fStringResponse.get();
        assertThat(stringResp._1).isEqualTo(200);
        assertThat(stringResp._2).isEqualTo("{\"experiments\":{\"experiments\":{\"id\":{\"variant\":\"A\"}}},\"features\":{\"features\":{\"test1\":{\"active\":true}}},\"configurations\":{\"configs\":{\"test\":{\"value\":2}}}}");
    }

    @After
    public void stopAll() {
        TestKit.shutdownActorSystem(system);
    }

}
