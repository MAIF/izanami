package izanami

import java.time.ZoneId

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import izanami.IzanamiBackend.SseBackend
import izanami.scaladsl.IzanamiClient

class ClientSpec extends IzanamiSpec {

  "client config" should {
    "config" in {

      //#configure-client
      implicit val system = ActorSystem(
        "izanami-client",
        ConfigFactory.parseString("""
          izanami-example.blocking-io-dispatcher {
            type = Dispatcher
            executor = "thread-pool-executor"
            thread-pool-executor {
              fixed-pool-size = 32
            }
            throughput = 1
          }
        """)
      )

      val client = IzanamiClient(
        ClientConfig(
          host = "http://localhost:9000",
          clientId = Some("xxxx"),
          clientIdHeaderName = "Another-Client-Id-Header",
          clientSecret = Some("xxxx"),
          clientSecretHeaderName = "Another-Client-Id-Header",
          backend = SseBackend,
          pageSize = 50,
          zoneId = ZoneId.of("Europe/Paris"),
          dispatcher = "izanami-example.blocking-io-dispatcher"
        )
      )
      //#configure-client

      TestKit.shutdownActorSystem(system)
    }
  }

}
