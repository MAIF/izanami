package io.otoroshi.wasm4s

import io.otoroshi.wasm4s.impl.WasmVmPoolImpl

/**
  * Temporary bridge for cleaning wasm4s global pools when an Izanami instance
  * stops.
  *
  * wasm4s keeps pools in a JVM-wide registry and exposes their cleanup methods
  * with `private[wasm4s]`. Keeping this bridge in the wasm4s package lets
  * Izanami release those pools until wasm4s provides a public lifecycle API.
  */
object IzanamiWasm4sCleanup {

  def clearAllPools(): Unit = {
    WasmVmPoolImpl.allInstances().foreach { case (key, pool) =>
      pool.destroyCurrentVms()
      pool.close()
      WasmVmPoolImpl.removePlugin(key)
    }
  }
}
