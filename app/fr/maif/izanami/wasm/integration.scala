package fr.maif.izanami.wasm

import akka.stream.Materializer
import fr.maif.izanami.env.Env
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.wasm.host.scala.HostFunctions
import io.otoroshi.wasm4s.scaladsl.security.TlsConfig
import io.otoroshi.wasm4s.scaladsl.{CacheableWasmScript, WasmConfiguration, WasmIntegrationContext, WasmoSettings}
import org.extism.sdk.{HostFunction, HostUserData}
import play.api.Logger
import play.api.libs.ws.WSRequest

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

class IzanamiWasmIntegrationContext(env: Env) extends WasmIntegrationContext {

  implicit val ec: ExecutionContext = env.executionContext
  implicit val ev: Env = env

  val logger: Logger = Logger("izanami-wasm")
  val materializer: Materializer = env.materializer
  val executionContext: ExecutionContext = env.executionContext
  val selfRefreshingPools: Boolean = false
  val wasmCacheTtl: Long = env.typedConfiguration.wasm.cache.ttl
  val wasmQueueBufferSize: Int = env.typedConfiguration.wasm.queue.buffer.size
  val wasmScriptCache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  val wasmExecutor: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
  )

  override def url(path: String, tlsConfig: Option[TlsConfig] = None): WSRequest = {
    // TODO: support mtls calls
    env.Ws.url(path)
  }

  override def wasmoSettings: Future[Option[WasmoSettings]] = env.datastores.configuration.readWasmConfiguration().future

  override def wasmConfig(path: String): Future[Option[WasmConfiguration]] = {
    val parts = path.split("/")
    val tenant = parts.head
    val id = parts.last
    env.datastores.features.readScriptConfig(tenant, id)
  }

  override def wasmConfigs(): Future[Seq[WasmConfiguration]] = env.datastores.features.readAllLocalScripts()

  override def hostFunctions(config: WasmConfiguration, pluginId: String): Array[HostFunction[_ <: HostUserData]] = {
    HostFunctions.getFunctions(config.asInstanceOf[WasmConfig], pluginId, None)
  }
}