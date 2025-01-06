package fr.maif.izanami.v1

import org.apache.pekko.util.ByteString
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.errors.NoWasmManagerConfigured
import fr.maif.izanami.v1.OldScripts.generateNewScriptContent
import io.otoroshi.wasm4s.scaladsl.ApikeyHelper
import io.otoroshi.wasm4s.scaladsl.WasmoSettings
import org.mozilla.javascript.Parser
import org.mozilla.javascript.ast._
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

import java.time.Duration
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class WasmManagerClient(env: Env)(implicit ec: ExecutionContext) {
  implicit val logger: Logger = env.logger
  val httpClient: WSClient    = env.Ws

  def wasmoConfiguration: Option[WasmoSettings] = env.datastores.configuration.readWasmConfiguration()

  def transferLegacyJsScript(
      name: String,
      content: String,
      local: Boolean
  ): Future[Either[IzanamiError, (String, ByteString)]] = {
    wasmoConfiguration match {
      case None                   => Future.successful(Left(NoWasmManagerConfigured()))
      case Some(w: WasmoSettings) => {
        createScript(
          name,
          generateNewScriptContent(content),
          config = w,
          local = local
        ).map(t => Right(t))
      }
    }
  }

  def createScript(
      name: String,
      content: String,
      config: WasmoSettings,
      local: Boolean
  ): Future[(String, ByteString)] = {
    if (local) {
      build(config, name, content)
        .map(queueId => queueId)
        .flatMap(queueId => retrieveLocallyBuildedWasm(s"$name-1.0.0-dev", config).map(bs => (queueId, bs)))
    } else {
      val pluginName = s"$name-1.0.0"
      val body = Json.obj(
        "metadata" -> Json.obj(
          "type"    -> "js",
          "name"    -> name,
          "release" -> true,
          "local"   -> false
        ),
        "files"    -> Json.arr(
          Json.obj(
            "name"    -> "index.js",
            "content" -> content
          ),
          Json.obj(
            "name"    -> "plugin.d.ts",
            "content" ->
              """declare module 'main' {
                |  export function execute(): I32;
                |}""".stripMargin
          )
        )
      )
      if (logger.isDebugEnabled) {
        logger.debug(s"Creating script : POST ${config.url}/api/plugins with body $body and auth headers ${ApikeyHelper.generate(config)}")
      }

      for (
        pluginId <- httpClient
                      .url(s"${config.url}/api/plugins")
                      .withHttpHeaders(("Content-Type", "application/json"), ApikeyHelper.generate(config))
                      .post(body)
                      .map(response => {
                        val json = response.json
                        logger.debug(s"Resoponse from ${config.url}/api/plugins is $json")
                        (json \ "plugin_id").as[String]
                      });
        _        <- buildAndSave(pluginId, config = config);
        wasm     <- retrieveBuildedWasm(pluginName, config)
      ) yield {
        (pluginName, wasm)
      }
    }
  }

  def buildAndSave(
      pluginId: String,
      config: WasmoSettings
  ): Future[String] = {
    if (logger.isDebugEnabled) {
      logger.debug(
        s"Building plugin for release : POST ${config.url}/api/plugins/$pluginId/build?release=true and auth header ${ApikeyHelper
          .generate(config)}"
      )
    }
    httpClient
      .url(s"${config.url}/api/plugins/$pluginId/build?release=true")
      .withHttpHeaders(ApikeyHelper.generate(config))
      .post("")
      .map(response => {
        val json = response.json
        logger.debug(s"Response from ${config.url}/api/plugins/$pluginId/build?release=true is $json")
        (json \ "queue_id").as[String]
      })
  }

  def build(
      config: WasmoSettings,
      name: String,
      content: String
  ): Future[String] = {
    httpClient
      .url(s"${config.url}/api/plugins/build")
      .withHttpHeaders(("Content-Type", "application/json"))
      .withHttpHeaders(ApikeyHelper.generate(config))
      .post(
        Json.obj(
          "metadata" -> Json.obj(
            "type"    -> "js",
            "name"    -> name,
            "version" -> "1.0.0",
            "local"   -> true,
            "release" -> false
          ),
          "files"    -> Json.arr(
            Json.obj("name" -> "index.js", "content" -> content),
            Json.obj(
              "name"        -> "plugin.d.ts",
              "content"     ->
              """declare module 'main' {
                |  export function execute(): I32;
                |}""".stripMargin
            ),
            Json.obj(
              "name"        -> "package.json",
              "content"     ->
              s"""
                {
                  "name": "$name",
                  "version": "1.0.0",
                  "devDependencies": {
                    "esbuild": "^0.17.9"
                  }
                }
                """.stripMargin
            )
          )
        )
      )
      .map(response => (response.json \ "queue_id").as[String])
  }

  private def retrieveBuildedWasm(name: String, config: WasmoSettings): Future[ByteString] = {
    tryUntil(
      () => {
        httpClient
          .url(s"${config.url}/api/wasm/$name")
          .withHttpHeaders(ApikeyHelper.generate(config))
          .get()
          .map(resp => if (resp.status >= 400) throw new RuntimeException("Bad status") else resp.bodyAsBytes)
      },
      Duration.ofMinutes(1)
    )
  }

  private def retrieveLocallyBuildedWasm(id: String, config: WasmoSettings): Future[ByteString] = {
    tryUntil(
      () => {
        httpClient
          .url(s"${config.url}/local/wasm/$id")
          .withHttpHeaders(ApikeyHelper.generate(config))
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

object OldScripts {
  // TODO change execute name to something with less collision risks
  def generateNewScriptContent(oldScript: String): String = {
    s"""
       |export function execute() {
       |  let context = JSON.parse(Host.inputString());
       |
       |  let enabledCallback = () => Host.outputString(JSON.stringify(true));
       |  let disabledCallback = () => Host.outputString(JSON.stringify(false));
       |
       |  enabled(context, enabledCallback, disabledCallback);
       |
       |  return 0;
       |}
       |
       |${oldScript}
       |""".stripMargin
  }

  def doesUseHttp(script: String): Boolean = {
    val astRoot                     = new Parser().parse(script, "tmp.js", 1)
    var paramName: Option[String]   = None
    var hasFunctionEnoughParameters = true
    var foundUsage                  = false
    var shouldSkipFirst             = true
    astRoot.visitAll(new NodeVisitor() {
      override def visit(node: AstNode): Boolean = {
        node match {
          case node: FunctionNode      => {
            val params = node.getParams
            if (node.getName == "enabled" && (node.getParent == null || node.getParent.getParent == null)) {
              hasFunctionEnoughParameters = params.size() >= 4
              if (params.size() >= 4 && params.get(3).isInstanceOf[Name]) {
                val scope = params.get(3).asInstanceOf[Name].getDefiningScope
                paramName = Some(params.get(3).asInstanceOf[Name].getIdentifier)
              }

            }
          }
          case fc: FunctionCall        => {
            if (fc.getTarget.isInstanceOf[Name]) {
              val name      = fc.getTarget.asInstanceOf[Name].getIdentifier
              val nameMatch = paramName.contains(name)
              if (nameMatch) {
                foundUsage = true
              }
            }
          }
          case es: ExpressionStatement => {
            es.getExpression.visit(this)
          }
          case name: Name              => {
            foundUsage = paramName
              .map(n => n == name.getIdentifier)
              .map(res => {
                if (res && shouldSkipFirst) {
                  shouldSkipFirst = false
                  foundUsage
                } else {
                  res
                }
              })
              .getOrElse(foundUsage)
          }
          case s @ _                   => ()
        }
        hasFunctionEnoughParameters && !foundUsage
      }
    })
    foundUsage
  }

  def findUsageOf(node: AstNode, name: String): Boolean = {
    var found = false
    node.visit(new NodeVisitor() {
      override def visit(node: AstNode): Boolean = {
        node match {
          case fc: FunctionCall => {
            fc.getArguments
              .stream()
              .filter(n => n.isInstanceOf[Name])
              .map(n => n.asInstanceOf[Name])
              .filter(n => n.getIdentifier == name)
              .findFirst()
              .ifPresent(_ => found = true)
          }
          case _                => {}
        }
        true
      }
    })
    found
  }
}
