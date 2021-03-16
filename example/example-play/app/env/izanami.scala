package env

import izanami.{ClientConfig, IzanamiBackend}
import izanami.scaladsl.IzanamiClient

object Izanami {

  def izanamiConfig(izanamiConf: IzanamiConf): ClientConfig =
    (for {
      clientIdHeaderName     <- izanamiConf.clientIdHeaderName
      clientSecretHeaderName <- izanamiConf.clientSecretHeaderName
    } yield {
      ClientConfig(
        host = izanamiConf.host,
        clientId = izanamiConf.clientId,
        clientSecret = izanamiConf.clientSecret,
        clientIdHeaderName = clientIdHeaderName,
        clientSecretHeaderName = clientSecretHeaderName,
        backend = IzanamiBackend.SseBackend
      )
    }).getOrElse {
      ClientConfig(
        host = izanamiConf.host,
        clientId = izanamiConf.clientId,
        clientSecret = izanamiConf.clientSecret,
        backend = IzanamiBackend.SseBackend
      )
    }

}
