package fr.maif.izanami.wasm

import io.otoroshi.wasm4s.scaladsl.WasmIntegration

class WasmRelatedStuff(integration: =>WasmIntegration, val isWasmAllowed: Boolean) {
  def wasmIntegration: WasmIntegration = integration
}
