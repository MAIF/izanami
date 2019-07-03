package domains.feature

import java.time.{LocalDateTime, ZoneId}
import java.time.temporal.{ChronoUnit, TemporalUnit}

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import cats.Applicative
import cats.effect.{Effect, IO}
import domains.{ImportResult, Key}
import domains.script.GlobalScript.GlobalScriptKey
import domains.script.Script.ScriptCache
import domains.script._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.selenium.WebBrowser.Query
import play.api.libs.json.{JsSuccess, JsValue, Json}
import store.PagingResult
import store.Result.Result
import test.IzanamiSpec

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import scala.util.Random

class FeatureSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience {

  "Feature Deserialisation" must {

    "Deserialize DefaultFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "NO_STRATEGY"
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(DefaultFeature(Key("id"), true))

    }

    "Deserialize GlobalScriptFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "GLOBAL_SCRIPT",
          |   "parameters": { "ref": "ref" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(GlobalScriptFeature(Key("id"), true, "ref"))

    }

    "Deserialize ScriptFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "SCRIPT",
          |   "parameters": { "script": "script" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(ScriptFeature(Key("id"), true, JavascriptScript("script")))

    }

    "Deserialize ReleaseDateFeature" in {
      import FeatureInstances._
      val json =
        Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "RELEASE_DATE",
          |   "parameters": { "releaseDate": "01/01/2017 12:12:12" }
          |}
        """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(ReleaseDateFeature(Key("id"), true, LocalDateTime.of(2017, 1, 1, 12, 12, 12)))

    }

    "Deserialize ReleaseDateFeature other format" in {
      import FeatureInstances._
      val json =
        Json.parse("""
                     |{
                     |   "id": "id",
                     |   "enabled": true,
                     |   "activationStrategy": "RELEASE_DATE",
                     |   "parameters": { "releaseDate": "01/01/2017 12:12" }
                     |}
                   """.stripMargin)

      val result = json.validate[Feature]
      result mustBe an[JsSuccess[_]]

      result.get must be(ReleaseDateFeature(Key("id"), true, LocalDateTime.of(2017, 1, 1, 12, 12, 0)))

    }
  }

  "Feature Serialisation" should {

    "Serialize DefaultFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "NO_STRATEGY"
          |}
        """.stripMargin)

      Json.toJson(DefaultFeature(Key("id"), true)) must be(json)
    }

    "Deserialize GlobalScriptFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "GLOBAL_SCRIPT",
          |   "parameters": { "ref": "ref" }
          |}
        """.stripMargin)

      Json.toJson(GlobalScriptFeature(Key("id"), true, "ref")) must be(json)
    }

    "Deserialize ScriptFeature" in {
      import FeatureInstances._
      val json = Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "SCRIPT",
          |   "parameters": { "type": "javascript", "script": "script" }
          |}
        """.stripMargin)
      Json.toJson(ScriptFeature(Key("id"), true, JavascriptScript("script"))) must be(json)
    }

    "Deserialize ReleaseDateFeature" in {
      import FeatureInstances._
      val json =
        Json.parse("""
          |{
          |   "id": "id",
          |   "enabled": true,
          |   "activationStrategy": "RELEASE_DATE",
          |   "parameters": { "releaseDate": "01/01/2017 12:12:12" }
          |}
        """.stripMargin)
      Json.toJson(ReleaseDateFeature(Key("id"), true, LocalDateTime.of(2017, 1, 1, 12, 12, 12))) must be(json)
    }
  }

  "Feature graph" must {
    "Serialization must be ok" in {
      val features = List(DefaultFeature(Key("a"), true),
                          DefaultFeature(Key("a:b"), false),
                          DefaultFeature(Key("a:b:c"), true),
                          DefaultFeature(Key("a:b:d"), false))

      import cats._
      import cats.effect.implicits._
      import FeatureInstances._
      implicit val system                                = ActorSystem("test")
      implicit val globalScript: GlobalScriptService[IO] = fakeGlobalScriptRepository[IO]
      implicit val cache: ScriptCache[IO]                = fakeCache[IO]
      implicit val mat                                   = ActorMaterializer()
      implicit def isActive                              = FeatureInstances.isActive[IO]

      val graph = Source(features)
        .via(
          Feature.toGraph[IO](
            Json.obj(),
            null
          )
        )
        .runWith(Sink.head)

      graph.futureValue must be(
        Json.obj(
          "a" -> Json.obj(
            "active" -> true,
            "b" -> Json.obj(
              "active" -> false,
              "c"      -> Json.obj("active" -> true),
              "d"      -> Json.obj("active" -> false)
            )
          )
        )
      )
    }
  }

  def fakeCache[F[_]: Applicative]: ScriptCache[F] = new ScriptCache[F] {
    override def get[T: ClassTag](id: String): F[Option[T]]      = Applicative[F].pure(None)
    override def set[T: ClassTag](id: String, value: T): F[Unit] = Applicative[F].pure(())
  }

  def fakeGlobalScriptRepository[F[_]: Effect]: GlobalScriptService[F] = new GlobalScriptService[F] {
    override def create(id: GlobalScriptKey, data: GlobalScript): F[Result[GlobalScript]]                         = ???
    override def update(oldId: GlobalScriptKey, id: GlobalScriptKey, data: GlobalScript): F[Result[GlobalScript]] = ???
    override def delete(id: GlobalScriptKey): F[Result[GlobalScript]]                                             = ???
    override def deleteAll(query: store.Query): F[Result[Done]]                                                   = ???
    override def getById(id: GlobalScriptKey): F[Option[GlobalScript]]                                            = ???
    override def findByQuery(query: store.Query, page: Int, nbElementPerPage: Int): F[PagingResult[GlobalScript]] = ???
    override def findByQuery(query: store.Query): Source[(GlobalScriptKey, GlobalScript), NotUsed]                = ???
    override def count(query: store.Query): F[Long]                                                               = ???
    override def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]        = ???
  }

  "Date range feature" must {
    "active" in {
      val from    = LocalDateTime.now(ZoneId.of("Europe/Paris")).minus(1, ChronoUnit.HOURS)
      val to      = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.HOURS)
      val feature = DateRangeFeature(Key("key"), true, from = from, to = to)

      import cats._
      DateRangeFeatureInstances.isActive[Id].isActive(feature, Json.obj(), null).getOrElse(false) must be(true)
    }

    "inactive" in {
      val from    = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(1, ChronoUnit.MINUTES)
      val to      = LocalDateTime.now(ZoneId.of("Europe/Paris")).plus(2, ChronoUnit.HOURS)
      val feature = DateRangeFeature(Key("key"), true, from = from, to = to)

      import cats._
      DateRangeFeatureInstances.isActive[Id].isActive(feature, Json.obj(), null).getOrElse(false) must be(false)
    }
  }

  "Percentage feature" must {
    "Calc ratio" in {
      val feature = PercentageFeature(Key("key"), true, 60)

      val count: Int = calcPercentage(feature) { i =>
        s"string-number-$i"
      }

      count must (be > 55 and be < 65)

      val count2: Int = calcPercentage(feature) { i =>
        Random.nextString(50)
      }

      count2 must (be > 55 and be < 65)

    }
  }

  private def calcPercentage(feature: PercentageFeature)(mkString: Int => String) = {

    import cats._
    val count = (0 to 1000)
      .map { i =>
        val isActive =
          PercentageFeatureInstances
            .isActive[Id]
            .isActive(feature, Json.obj("id" -> mkString(i)), null)
            .getOrElse(false)
        isActive
      }
      .count(identity) / 10
    count
  }
}
