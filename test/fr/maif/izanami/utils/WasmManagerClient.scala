package fr.maif.izanami.utils

import org.apache.pekko.util.ByteString
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import org.apache.commons.io.output.ByteArrayOutputStream
import play.api.libs.json.{JsBoolean, Json}
import play.api.libs.ws.WSClient

import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.Objects
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.ws.writeableOf_JsValue
import play.api.libs.ws.writeableOf_String


case class WasmManagerClient(client: WSClient, url: String)(implicit ec: ExecutionContext) {
  def createScript(
                    name: String,
                    content: String,
                    local: Boolean
                  ): Future[(String, ByteString)] = {
    val pluginName = s"$name-1.0.0-dev.wasm" // Fixme when release will be default, remove "-dev"
    if(local) {
      build(name, content)
        .map(queueId => queueId)
        .flatMap(queueId => retrieveLocallyBuildedWasm(queueId).map(bs => (queueId, bs)))
    } else {
      for (
        pluginId <- client.url(s"$url/api/plugins")
          .withHttpHeaders(("Content-Type", "application/json"), ("Authorization" -> "Basic YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA=="))
          .post(Json.obj(
            "metadata" -> Json.obj(
              "type" -> "js",
              "name" -> name,
              "template" -> "izanami"
            ),
            "files" -> Json.arr(
              Json.obj("name" -> "index.js", "content" -> content)
            )
          ))
          .map(response => (response.json \ "plugin_id").as[String]);
        _ <- buildAndSave(pluginId);
        wasm <- retrieveBuildedWasm(pluginName)
      ) yield {
        (pluginName, wasm)
      }
    }
  }

  def buildAndSave(
                    pluginId: String,
                    release: Boolean = false
                  ): Future[String] = {
    client
      .url(s"$url/api/plugins/$pluginId/build?release=$release")
      .withHttpHeaders("Authorization" -> "Basic YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA==")
      .post("")
      .map(response => (response.json \ "queue_id").as[String])
  }

  def build(
             name: String,
             content: String
           ): Future[String] = {
    client
      .url(s"$url/api/plugins/build")
      .withHttpHeaders(("Content-Type", "application/json"))
        .withHttpHeaders("Authorization" -> "Basic YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA==")
      .post(
        Json.obj(
          "metadata" -> Json.obj(
            "type"    -> "js",
            "name"    -> name,
            "version" -> "1.0.0",
            "release" -> JsBoolean(true),
            "local"   -> true,
            "template" -> "izanami"
          ),
          "files"    -> Json.arr(
            Json.obj("name" -> "index.js" , "content" -> content),
            Json.obj("name" -> "package.json", "content" ->
              s"""
                 |{
                 |  "name": "$name",
                 |  "version": "1.0.0",
                 |  "devDependencies": {
                 |    "esbuild": "^0.17.9"
                 |  }
                 |}
                 |""".stripMargin)
          )
        )
      )
      .map(response => (response.json \ "queue_id").as[String])
  }

  private def retrieveBuildedWasm(name: String): Future[ByteString] = {
    tryUntil(
      () => {
        client
          .url(s"http://localhost:5001/wasm/$name")
          .withHttpHeaders("Authorization" -> "Basic YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA==")
          .get()
          .map(resp => if (resp.status >= 400) throw new RuntimeException("Bad status") else resp.bodyAsBytes)
      },
      Duration.ofMinutes(1)
    )
  }

  private def retrieveLocallyBuildedWasm(id: String): Future[ByteString] = {
    tryUntil(
      () => {
        client
          .url(s"http://localhost:5001/local/wasm/$id")
          .withHttpHeaders("Authorization" -> "Basic YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA==")
          .get()
          .map(resp => if (resp.status >= 400) throw new RuntimeException("Bad status") else resp.bodyAsBytes)
      },
      Duration.ofMinutes(1)
    )
  }

  def tryUntil[T](op: () => Future[T], duration: Duration): Future[T] = {
    val start = System.currentTimeMillis()

    def act(count: Int = 0): Future[T] = {
      if (Duration.ofMillis(System.currentTimeMillis() - start).compareTo(duration) > 0) {
        throw new RuntimeException(s"Failed attempted operation, tried ${count} times")
      }
      op().recoverWith(ex => {
        Thread.sleep(1000L)
        act(count + 1)
      })
    }

    act(0)
  }
}
