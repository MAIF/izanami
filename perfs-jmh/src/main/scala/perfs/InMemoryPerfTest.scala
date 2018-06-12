package perfs

import java.util.concurrent.{CompletionStage, TimeUnit}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import domains.Key
import domains.feature.DefaultFeature
import env.{DbDomainConfig, DbDomainConfigDetails, InMemory}
import org.openjdk.jmh.annotations._
import perfs.InMemoryPerfTest.BenchmarkState
import play.api.libs.json.{JsValue, Json}
import store.PagingResult
import store.memory.InMemoryJsonDataStore

import scala.concurrent.Await
import scala.concurrent.duration.DurationLong

object InMemoryPerfTest {

  @State(Scope.Benchmark)
  class BenchmarkState {
    implicit val actorSystem = ActorSystem()
    implicit val mat         = ActorMaterializer()
    val store                = InMemoryJsonDataStore(DbDomainConfig(InMemory, DbDomainConfigDetails("test")), actorSystem)
    val init = Source(0 to 2000)
      .mapAsyncUnordered(50) { i =>
        val key = Key(s"a:key:$i")
        store.create(key, Json.toJson(DefaultFeature(key, enabled = true)))
      }
      .runWith(Sink.ignore)

    Await.result(init, 20.seconds)
  }
}

@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class InMemoryPerfTest {

  @Benchmark
  def list(state: BenchmarkState): CompletionStage[PagingResult[JsValue]] = {
    import scala.compat.java8.FutureConverters._
    state.store.getByIdLike(patterns = Seq("*"), page = 1, nbElementPerPage = 15).toJava
  }

}
