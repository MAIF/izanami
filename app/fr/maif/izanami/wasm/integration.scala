package fr.maif.izanami.wasm

import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.wasm.host.scala.HostFunctions
import io.otoroshi.wasm4s.scaladsl.CacheableWasmScript
import io.otoroshi.wasm4s.scaladsl.WasmConfiguration
import io.otoroshi.wasm4s.scaladsl.WasmIntegrationContext
import io.otoroshi.wasm4s.scaladsl.WasmoSettings
import io.otoroshi.wasm4s.scaladsl.security.TlsConfig
import org.apache.pekko.stream.Materializer
import org.extism.sdk.HostFunction
import org.extism.sdk.HostUserData
import play.api.Logger
import play.api.libs.ws.WSRequest

import java.util.concurrent.Executors
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import fr.maif.izanami.datastores.ConfigurationDatastore
import fr.maif.izanami.datastores.FeaturesDatastore
import fr.maif.izanami.Wasm
import play.api.libs.ws.WSClient

class IzanamiWasmIntegrationContext(
  configurationDatastore: ConfigurationDatastore,
  featureDatastore: FeaturesDatastore,
  wasmConfiguration: Wasm,
  httpClient: WSClient
  )(implicit ec: ExecutionContext, mat: Materializer) extends WasmIntegrationContext {
  val logger: Logger = Logger("izanami-wasm")
  val selfRefreshingPools: Boolean = false
  val wasmCacheTtl: Long = wasmConfiguration.cache.ttl
  val wasmQueueBufferSize: Int = wasmConfiguration.queue.buffer.size
  val wasmScriptCache: TrieMap[String, CacheableWasmScript] =
    new TrieMap[String, CacheableWasmScript]()
  val wasmExecutor: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(
      32,
      (Runtime.getRuntime.availableProcessors * 4) + 1
    ))
  )

  override def url(
      path: String,
      tlsConfig: Option[TlsConfig] = None
  ): WSRequest = {
    // TODO: support mtls calls
    httpClient.url(path)
  }

  override def wasmoSettings: Future[Option[WasmoSettings]] =
    configurationDatastore.readWasmConfiguration().future

  override def wasmConfig(path: String): Future[Option[WasmConfiguration]] = {
    val parts = path.split("/")
    val tenant = parts.head
    val id = parts.last
    featureDatastore.readScriptConfig(tenant, id)
  }

  override def wasmConfigs(): Future[Seq[WasmConfiguration]] =
    featureDatastore.readAllLocalScripts()

  override def hostFunctions(
      config: WasmConfiguration,
      pluginId: String
  ): Array[HostFunction[_ <: HostUserData]] = {
    HostFunctions.getFunctions(config.asInstanceOf[WasmConfig], pluginId, None, httpClient = httpClient, wasmIntegrationContext = this)
  }
}
