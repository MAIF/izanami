package domains.webhook

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.{JsSuccess, Json}
import test.IzanamiSpec

class WebhookSpec
    extends IzanamiSpec
    with ScalaFutures
    with IntegrationPatience {
  "Webhook" should {

    "read json" in {
      import Webhook._
      val date = DateTimeFormatter.ISO_TIME.format(LocalDateTime.now())
      val json = Json.obj("clientId" -> "my:path",
                          "callbackUrl" -> "http://localhost:5000",
                          "notificationPattern" -> "*",
                          "headers" -> Json.obj(),
                          "created" -> date)
      val result = json.validate[Webhook]
      result mustBe an[JsSuccess[_]]
    }

    "read json missing fields" in {
      import Webhook._
      val json =
        Json.obj("clientId" -> "my:path",
                 "callbackUrl" -> "http://localhost:5000",
                 "notificationPattern" -> "*")
      val result = json.validate[Webhook]
      result mustBe an[JsSuccess[_]]
    }
  }

}
