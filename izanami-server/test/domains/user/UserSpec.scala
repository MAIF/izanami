package domains.user
import cats.effect.IO
import domains.{AuthorizedPattern, Key}
import domains.events.Events
import libs.crypto.Sha
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.JsValue
import store.Result.Result
import store.memory.InMemoryJsonDataStore
import test.{IzanamiSpec, TestEventStore}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

class UserSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience {

  "User" must {

    "Hash passsword" in {
      val service = userService()

      val key                   = Key("user1")
      val ragnard               = User("user1", "Ragnard", "ragnard@gmail.com", Some("ragnar123456"), false, AuthorizedPattern("*"))
      val created: Result[User] = service.create(key, ragnard).unsafeRunSync()

      created mustBe Right(ragnard.copy(password = Some(Sha.hexSha512("ragnar123456"))))

      service.getById(key).unsafeRunSync() mustBe Some(ragnard.copy(password = Some(Sha.hexSha512("ragnar123456"))))

      val toUpdate              = ragnard.copy(password = Some("ragnar1234"))
      val updated: Result[User] = service.update(key, key, toUpdate).unsafeRunSync()
      updated mustBe Right(toUpdate.copy(password = Some(Sha.hexSha512("ragnar1234"))))

      service.getById(key).unsafeRunSync() mustBe Some(toUpdate.copy(password = Some(Sha.hexSha512("ragnar1234"))))
    }

  }

  def userService(store: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue],
                  events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty): UserService[IO] =
    new UserServiceImpl[IO](
      new InMemoryJsonDataStore("users", store),
      new TestEventStore[IO](events)
    )

}
