package izanami.javadsl;

import akka.actor.ActorSystem;
import akka.testkit.javadsl.TestKit;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.typesafe.config.ConfigFactory;
import io.vavr.concurrent.Future;
import izanami.ClientConfig;
import izanami.Strategies;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import izanami.javadsl.*;
import org.junit.Test;
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

        //#configure-client
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
        //#configure-client

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
    public void features() {

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

        //#configure-feature-client
        FeatureClient featureClient = izanamiClient.featureClient(
                Strategies.fetchStrategy(),
                Features.features(
                        Features.feature("my:feature", false)
                )
        );
        //#configure-feature-client

        Future<Boolean> futureConfig = featureClient.checkFeature("my:feature");

        futureConfig.onSuccess(config ->
                System.out.println(config)
        );

        assertThat(futureConfig.get()).isTrue();
        verify(postRequestedFor(urlMatching("/api/features/my:feature/check")));
        //wireMockRule.findAllUnmatchedRequests().forEach(System.out::println);
    }

    @After
    public void stopAll() {
        TestKit.shutdownActorSystem(system);
    }

}
