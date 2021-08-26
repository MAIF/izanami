package domains.lock

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.data.NonEmptyList
import domains.apikey.Apikey
import domains.auth.AuthInfo
import domains.configuration.PlayModule
import domains.errors.IzanamiErrors.ToErrorsOps
import domains.errors.{DataShouldExists, UnauthorizedByLock}
import domains.events.Events.{LockCreated, LockDeleted, LockUpdated}
import domains.events.{EventStore, Events}
import domains.feature._
import domains.script.RunnableScriptModule.RunnableScriptModuleProd
import domains.script.{CacheService, GlobalScriptDataStore, RunnableScriptModule, ScriptCache}
import domains.{AuthorizedPatterns, Key}
import env.configuration.IzanamiConfigModule
import libs.logs.ZLogger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.Environment
import play.api.libs.json.{JsSuccess, Json}
import store.Query
import store.memory.InMemoryJsonDataStore
import test.{FakeConfig, IzanamiSpec, TestEventStore}
import zio.blocking.Blocking
import zio.{Runtime, Task, ULayer, ZLayer}

import scala.collection.mutable
import scala.reflect.ClassTag

class LockSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience with BeforeAndAfterAll {

  implicit val runtime     = Runtime.default
  implicit val actorSystem = ActorSystem()

  override def afterAll(): Unit = TestKit.shutdownActorSystem(actorSystem)

  "Lock Serialization / Deserialization" must {

    "Deserialization" in {
      import LockInstances._
      val json   = Json.parse("""
          |{
          |   "id": "feature:id",
          |   "locked": true
          |}
          |""".stripMargin)
      val result = json.validate[IzanamiLock]

      result mustBe an[JsSuccess[_]]
      result.get must be(IzanamiLock(Key("feature:id"), locked = true))
    }

    "Serialization" in {
      val json = Json.parse("""
          |{
          |   "id": "feature:id",
          |   "locked": true
          |}
          |""".stripMargin)
      LockInstances.format.writes(IzanamiLock(Key("feature:id"), locked = true)) must be(json)
    }
  }

  private val authInfo = Some(Apikey("1", "name", "****", AuthorizedPatterns.All, true))

  "LockService" must {
    "create" in {
      val id            = Key("feature:create")
      val lock          = IzanamiLock(id, true)
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val ctx           = testLockContext(events = events, lockDataStore = lockDataStore)

      val created = run(ctx)(LockService.create(id, lock))

      created must be(lock)

      lockDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 1
      inside(events.head) {
        case LockCreated(i, l, _, _, auth) =>
          i must be(id)
          l must be(lock)
          auth must be(authInfo)
      }
    }

    "update" in {
      val id            = Key("feature:update")
      val lock          = IzanamiLock(id, true)
      val newLock       = IzanamiLock(id, true)
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val ctx           = testLockContext(events = events, lockDataStore = lockDataStore)

      val u = for {
        _       <- LockService.create(id, lock)
        updated <- LockService.update(id, newLock)
      } yield updated

      val updated = run(ctx)(u)
      updated must be(newLock)
      lockDataStore.inMemoryStore.contains(id) must be(true)
      events must have size 2
      inside(events.last) {
        case LockUpdated(i, oldLock, newLock, _, _, auth) =>
          i must be(id)
          oldLock must be(lock)
          newLock must be(newLock)
          auth must be(authInfo)
      }
    }

    "update changing id" in {
      val id            = Key("feature:update1")
      val newId         = Key("feature:update2")
      val lock          = IzanamiLock(id, true)
      val newLock       = IzanamiLock(newId, false)
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val ctx           = testLockContext(events = events, lockDataStore = lockDataStore)

      val test = for {
        _       <- LockService.create(id, lock)
        updated <- LockService.update(id, newLock)
      } yield updated

      run(ctx)(test)
      lockDataStore.inMemoryStore.contains(id) must be(false)
      lockDataStore.inMemoryStore.contains(newId) must be(true)

      inside(events.last) {
        case LockUpdated(i, old, _, _, _, auth) =>
          i must be(id)
          old must be(lock)
          newLock must be(newLock)
          auth must be(authInfo)
      }
    }

    "delete" in {
      val id            = Key("feature:delete")
      val lock          = IzanamiLock(id, true)
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val ctx           = testLockContext(events = events, lockDataStore = lockDataStore)

      val test = for {
        _       <- LockService.create(id, lock)
        deleted <- LockService.delete(id)
      } yield deleted

      run(ctx)(test)
      lockDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 2
      inside(events.last) {
        case LockDeleted(i, old, _, _, auth) =>
          i must be(id)
          old must be(lock)
          auth must be(authInfo)
      }
    }

    "delete empty data" in {
      val id            = Key("feature:deleteEmpty")
      val events        = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val ctx           = testLockContext(events = events, lockDataStore = lockDataStore)

      val deleted = run(ctx)(LockService.delete(id).either)
      deleted must be(Left(DataShouldExists(id).toErrors))
      lockDataStore.inMemoryStore.contains(id) must be(false)
      events must have size 0
    }

    "get by id" in {
      val id            = Key("feature:getbyid")
      val lock          = IzanamiLock(id, true)
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val ctx           = testLockContext(lockDataStore = lockDataStore)

      val test = for {
        _   <- LockService.create(id, lock)
        get <- LockService.getBy(id)
      } yield get

      val get = run(ctx)(test)

      get must contain(lock)
    }

    "find" in {

      val lock1         = IzanamiLock(Key("feature:find:a:b:c"), true)
      val lock2         = IzanamiLock(Key("feature:find:a:b:c:d"), true)
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val ctx           = testLockContext(lockDataStore = lockDataStore)

      val test = for {
        _    <- LockService.create(Key("feature:find:a:b"), IzanamiLock(Key("feature:find:a:b"), true))
        _    <- LockService.create(Key("feature:find:a:b:c"), lock1)
        _    <- LockService.create(Key("feature:find:a:b:c:d"), lock2)
        _    <- LockService.create(Key("feature:find:a:f"), IzanamiLock(Key("feature:find:a:f"), true))
        find <- LockService.findByQuery(Query.oneOf("feature:find:a:b:*"))
      } yield find

      val find = run(ctx)(test)

      find.filter(l => l.id.key.equals("feature:find:a:b:c")) must be(List(lock1))
      find.filter(l => l.id.key.equals("feature:find:a:b:c:d")) must be(List(lock2))
      find must have size 2
    }
  }

  "LockInfo Feature" must {

    // CREATE

    "create feature forbidden on the root if lock exist (1)" in {
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = lockDataStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = lockDataStore)
      val featureAB     = DefaultFeature(Key("a:b"), false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:a:b"), true)
        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // create feature on the root
        val id      = Key("a1")
        val feature = DefaultFeature(id, false, None)
        val create  = run(ctxFeature)(FeatureService.create(feature.id, feature).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(feature.id, featureAB.id)))
        featuresStore.inMemoryStore.contains(feature.id) must be(false)
      }
    }

    "create feature forbidden on the locked path (2) & (4)" in {
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = lockDataStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = lockDataStore)
      val featureAB     = DefaultFeature(Key("a:b"), false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:a:b"), true)
        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // create feature on the locked path (4)
        val id      = Key("a")
        val feature = DefaultFeature(id, false, None)
        val create  = run(ctxFeature)(FeatureService.create(feature.id, feature).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(feature.id, featureAB.id)))
        featuresStore.inMemoryStore.contains(feature.id) must be(false)
      }

      { // create feature from the locked path (2)
        val id      = Key("a:b1")
        val feature = DefaultFeature(id, false, None)
        val create  = run(ctxFeature)(FeatureService.create(feature.id, feature).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(feature.id, featureAB.id)))
        featuresStore.inMemoryStore.contains(feature.id) must be(false)
      }
    }

    "create feature after locked feature (3)" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)

      { // initial state
        val id      = Key("a:b")
        val feature = DefaultFeature(id, false, None)
        val lock    = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(feature.id, feature))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // create feature after locked feature
        val feature = DefaultFeature(Key("a:b:c"), false, None)
        run(ctxFeature)(FeatureService.create(feature.id, feature).either)
        featuresStore.inMemoryStore.contains(feature.id) must be(true)
      }
    }

    "create feature in another branch of lock (5)" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)
      val id            = Key("a:b")

      { // initial state
        val feature = DefaultFeature(id, false, None)
        val lock    = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(feature.id, feature))
        run(ctxFeature)(FeatureService.create(Key("a1"), DefaultFeature(Key("a1"), false, None)))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // create feature forbidden if su path must created on locked path
        val featureTest = DefaultFeature(Key("a:b1:c1"), false, None)
        val create      = run(ctxFeature)(FeatureService.create(featureTest.id, featureTest).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureTest.id, id)))
        featuresStore.inMemoryStore.contains(featureTest.id) must be(false)
      }
      {
        // create feature in another branch of lock
        val featureTest = DefaultFeature(Key("a1:b1"), false, None)
        run(ctxFeature)(FeatureService.create(featureTest.id, featureTest).either)
        featuresStore.inMemoryStore.contains(featureTest.id) must be(true)
      }
    }

    // DELETE

    "delete feature forbidden on the root if lock exist (1)" in {
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = lockDataStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = lockDataStore)
      val featureAB     = DefaultFeature(Key("a:b"), false, None)
      val featureA1     = DefaultFeature(Key("a1"), false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:a:b"), true)
        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureA1.id, featureA1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // create feature on the root
        val create = run(ctxFeature)(FeatureService.delete(featureA1.id).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureA1.id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(featureA1.id) must be(true)
      }
    }

    "delete feature forbidden on the locked path (2) & (4)" in {
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = lockDataStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = lockDataStore)
      val featureA      = DefaultFeature(Key("a"), false, None)
      val featureAB     = DefaultFeature(Key("a:b"), false, None)
      val featureAB1    = DefaultFeature(Key("a:b1"), false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:a:b"), true)
        run(ctxFeature)(FeatureService.create(featureA.id, featureA))
        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureAB1.id, featureAB1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // delete feature on the locked path (4)
        val id     = Key("a")
        val create = run(ctxFeature)(FeatureService.delete(id).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(id) must be(true)
      }

      { // delete feature from the locked path (2)
        val id     = Key("a:b1")
        val create = run(ctxFeature)(FeatureService.delete(id).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(id) must be(true)
      }
    }

    "delete feature after locked feature (3)" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)

      { // initial state
        val id      = Key("a:b")
        val feature = DefaultFeature(id, false, None)
        val lock    = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(feature.id, feature))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // delete feature after locked feature
        val id = Key("a:b:c")
        run(ctxFeature)(FeatureService.delete(id).either)
        featuresStore.inMemoryStore.contains(id) must be(false)
      }
    }

    "delete last feature after locked feature forbidden" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)
      val featureABC    = DefaultFeature(Key("a:b:c"), false, None)

      { // initial state
        val id   = Key("a:b")
        val lock = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(featureABC.id, featureABC))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // delete feature after locked feature
        val id     = Key("a:b:c")
        val create = run(ctxFeature)(FeatureService.delete(id).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(id) must be(true)
      }
    }

    "delete feature in another branch of lock (5)" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)
      val featureA1B1   = DefaultFeature(Key("a1:b1"), false, None)

      { // initial state
        val id        = Key("a:b")
        val featureAB = DefaultFeature(id, false, None)
        val lock      = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureA1B1.id, featureA1B1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // delete feature in another branch of lock
        run(ctxFeature)(FeatureService.delete(featureA1B1.id).either)
        featuresStore.inMemoryStore.contains(featureA1B1.id) must be(false)
      }
    }

    // UPDATE

    "update feature forbidden from the root if lock exist (1)" in {
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = lockDataStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = lockDataStore)
      val featureAB     = DefaultFeature(Key("a:b"), false, None)
      val featureA1     = DefaultFeature(Key("a1"), false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:a:b"), true)
        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureA1.id, featureA1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // update feature from the root
        val featureABC = DefaultFeature(Key("a:b:c"), false, None)
        val update     = run(ctxFeature)(FeatureService.update(featureA1.id, featureABC.id, featureABC).either)
        update mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureA1.id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(featureA1.id) must be(true)
        featuresStore.inMemoryStore.contains(featureABC.id) must be(false)
      }
    }

    "update feature forbidden to the root if lock exist (1)" in {
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = lockDataStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = lockDataStore)
      val featureABC    = DefaultFeature(Key("a:b:c"), false, None)

      { // initial state
        val featureAB = DefaultFeature(Key("a:b"), false, None)

        val lock = IzanamiLock(Key(s"feature:a:b"), true)
        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureABC.id, featureABC))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // update feature to the root
        val featureA1 = DefaultFeature(Key("a1"), false, None)
        val update    = run(ctxFeature)(FeatureService.update(featureABC.id, featureA1.id, featureA1).either)
        update mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureA1.id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(featureA1.id) must be(false)
        featuresStore.inMemoryStore.contains(featureABC.id) must be(true)
      }
    }

    "update feature forbidden from the locked path (2) & (4)" in {
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = lockDataStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = lockDataStore)
      val featureA      = DefaultFeature(Key("a"), false, None)
      val featureAB     = DefaultFeature(Key("a:b"), false, None)
      val featureABC    = DefaultFeature(Key("a:b:c"), false, None)
      val featureAB1    = DefaultFeature(Key("a:b1"), false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:a:b"), true)
        run(ctxFeature)(FeatureService.create(featureA.id, featureA))
        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureAB1.id, featureAB1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // update feature from the locked path (4)
        val create = run(ctxFeature)(FeatureService.update(featureA.id, featureABC.id, featureABC).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureA.id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(featureA.id) must be(true)
        featuresStore.inMemoryStore.contains(featureABC.id) must be(false)
      }

      { // from feature from the locked path (2)
        val create = run(ctxFeature)(FeatureService.update(featureAB1.id, featureABC.id, featureABC).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureAB1.id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(featureAB1.id) must be(true)
        featuresStore.inMemoryStore.contains(featureABC.id) must be(false)
      }
    }

    "update feature forbidden to the locked path (2) & (4)" in {
      val lockDataStore = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = lockDataStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = lockDataStore)
      val featureA      = DefaultFeature(Key("a"), false, None)
      val featureAB     = DefaultFeature(Key("a:b"), false, None)
      val featureABC    = DefaultFeature(Key("a:b:c"), false, None)
      val featureAB1    = DefaultFeature(Key("a:b1"), false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:a:b"), true)
        run(ctxFeature)(FeatureService.create(featureA.id, featureA))
        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureAB1.id, featureAB1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // update feature from the locked path (4)
        val create = run(ctxFeature)(FeatureService.update(featureA.id, featureABC.id, featureABC).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureA.id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(featureA.id) must be(true)
        featuresStore.inMemoryStore.contains(featureABC.id) must be(false)
      }

      { // from feature from the locked path (2)
        val create = run(ctxFeature)(FeatureService.update(featureAB1.id, featureABC.id, featureABC).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureAB1.id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(featureAB1.id) must be(true)
        featuresStore.inMemoryStore.contains(featureABC.id) must be(false)
      }
    }

    "update feature after locked feature (3)" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)
      val id            = Key("a:b")
      val featureAB     = DefaultFeature(id, false, None)
      val featureABC    = DefaultFeature(Key("a:b:c"), false, None)
      val featureABC1   = DefaultFeature(Key("a:b:c1"), false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureABC.id, featureABC))
        run(ctxFeature)(FeatureService.create(featureABC1.id, featureABC1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // update feature after locked feature
        val featureABC2 = DefaultFeature(Key("a:b:c2"), false, None)
        run(ctxFeature)(FeatureService.update(featureABC.id, featureABC2.id, featureABC2).either)
        featuresStore.inMemoryStore.contains(featureABC.id) must be(false)
        featuresStore.inMemoryStore.contains(featureABC2.id) must be(true)
      }
    }

    "update last feature after locked feature forbidden" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)
      val featureABC    = DefaultFeature(Key("a:b:c"), false, None)
      val featureA1     = DefaultFeature(Key("a1"), false, None)

      { // initial state
        val id   = Key("a:b")
        val lock = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(featureABC.id, featureABC))
        run(ctxFeature)(FeatureService.create(featureA1.id, featureA1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // update last feature after locked feature
        val featureA1B1 = DefaultFeature(Key("a1:b1"), false, None)
        val create      = run(ctxFeature)(FeatureService.update(featureABC.id, featureA1B1.id, featureA1B1).either)
        create mustBe Left(NonEmptyList.of(UnauthorizedByLock(featureABC.id, Key("a:b"))))
        featuresStore.inMemoryStore.contains(featureABC.id) must be(true)
        featuresStore.inMemoryStore.contains(featureA1B1.id) must be(false)
      }
    }

    "update feature in another branch of lock (5)" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)
      val featureA1B1   = DefaultFeature(Key("a1:b1"), false, None)

      { // initial state
        val id        = Key("a:b")
        val featureAB = DefaultFeature(id, false, None)
        val lock      = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureA1B1.id, featureA1B1))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // update feature in another branch of lock
        val featureA2B2 = DefaultFeature(Key("a2:b2"), false, None)
        run(ctxFeature)(FeatureService.update(featureA1B1.id, featureA2B2.id, featureA2B2).either)
        featuresStore.inMemoryStore.contains(featureA1B1.id) must be(false)
        featuresStore.inMemoryStore.contains(featureA2B2.id) must be(true)
      }
    }

    "update feature without change id" in {
      val locksStore       = new InMemoryJsonDataStore("lock-test")
      val featuresStore    = new InMemoryJsonDataStore("feature-test")
      val ctxLock          = testLockContext(lockDataStore = locksStore)
      val ctxFeature       = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)
      val id               = Key("a:b")
      val featureABDisable = DefaultFeature(id, false, None)

      { // initial state
        val lock = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(featureABDisable.id, featureABDisable))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // activate feature
        import FeatureInstances._
        val featureABEnable = DefaultFeature(id, true, None)
        run(ctxFeature)(FeatureService.update(featureABDisable.id, featureABEnable.id, featureABEnable).either)
        val maybeValue =
          featuresStore.inMemoryStore.get(featureABEnable.id).get.validate[Feature].get.enabled must be(true)
      }
    }

    "copy feature forbidden" in {
      val locksStore    = new InMemoryJsonDataStore("lock-test")
      val featuresStore = new InMemoryJsonDataStore("feature-test")
      val ctxLock       = testLockContext(lockDataStore = locksStore)
      val ctxFeature    = testFeatureContext(featureDataStore = featuresStore, lockDataStore = locksStore)
      val featureABC    = DefaultFeature(Key("a:b:c"), false, None)

      { // initial state
        val id        = Key("a:b")
        val featureAB = DefaultFeature(id, false, None)
        val lock      = IzanamiLock(Key(s"feature:${id.key}"), true)

        run(ctxFeature)(FeatureService.create(featureAB.id, featureAB))
        run(ctxFeature)(FeatureService.create(featureABC.id, featureABC))
        run(ctxLock)(LockService.create(lock.id, lock))
      }

      { // copy feature to locked path
        val featureA1 = DefaultFeature(Key("a1"), false, None)
        run(ctxFeature)(FeatureService.copyNode(featureABC.id, featureA1.id, true).either)
        featuresStore.inMemoryStore.contains(featureA1.id) must be(false)
        featuresStore.inMemoryStore.contains(featureABC.id) must be(true)
      }
    }
  }

  val env: Environment               = Environment.simple()
  val playModule: ULayer[PlayModule] = FakeConfig.playModule(actorSystem, env)

  def testLockContext(
      events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty,
      user: Option[AuthInfo.Service] = authInfo,
      lockDataStore: InMemoryJsonDataStore = new InMemoryJsonDataStore("lock-test")
  ): ZLayer[Any, Throwable, LockContext] =
    playModule ++ ZLogger.live ++ Blocking.live ++ EventStore.value(new TestEventStore(events)) ++
    AuthInfo.optValue(user) ++ LockDataStore.value(lockDataStore) >+> IzanamiConfigModule.value(FakeConfig.config)

  val testScript: RunnableScriptModuleProd                             = RunnableScriptModuleProd(env.classLoader)
  val runnableScriptModule: ZLayer[Any, Nothing, RunnableScriptModule] = RunnableScriptModule.value(testScript)

  def fakeCache: CacheService[String] = new CacheService[String] {
    override def get[T: ClassTag](id: String): Task[Option[T]] = Task.succeed(None)

    override def set[T: ClassTag](id: String, value: T): Task[Unit] = Task.succeed(())
  }

  def testFeatureContext(
      featureDataStore: InMemoryJsonDataStore,
      lockDataStore: InMemoryJsonDataStore
  ): ZLayer[Any, Throwable, FeatureContext] =
    playModule ++ ZLogger.live ++ Blocking.live ++
    ScriptCache.value(fakeCache) ++ EventStore.value(new TestEventStore(mutable.ArrayBuffer.empty)) ++ AuthInfo
      .optValue(authInfo) ++ GlobalScriptDataStore.value(new InMemoryJsonDataStore("global-script-test")) ++
    FeatureDataStore.value(featureDataStore) ++
    LockDataStore.value(lockDataStore) ++
    runnableScriptModule >+> IzanamiConfigModule.value(FakeConfig.config)
}
