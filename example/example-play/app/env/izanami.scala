package env

import izanami.{ClientConfig, IzanamiBackend}
import izanami.scaladsl.IzanamiClient

object Izanami {

  def izanamiConfig(izanamiConf: IzanamiConf): ClientConfig =
    ClientConfig(
      host = izanamiConf.host,
      clientId = izanamiConf.clientId,
      clientSecret = izanamiConf.clientSecret,
      backend = IzanamiBackend.SseBackend
    )

}
