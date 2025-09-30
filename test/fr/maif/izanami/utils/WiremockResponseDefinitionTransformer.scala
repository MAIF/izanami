package fr.maif.izanami.utils

import com.github.tomakehurst.wiremock.common.FileSource
import com.github.tomakehurst.wiremock.extension.{
  Parameters,
  ResponseDefinitionTransformer
}
import com.github.tomakehurst.wiremock.http.{
  HttpHeaders,
  Request,
  ResponseDefinition
}

import scala.collection.mutable.ArrayBuffer

class WiremockResponseDefinitionTransformer
    extends ResponseDefinitionTransformer {
  val requests: ArrayBuffer[(Request, HttpHeaders)] = ArrayBuffer()
  override def transform(
      request: Request,
      responseDefinition: ResponseDefinition,
      files: FileSource,
      parameters: Parameters
  ): ResponseDefinition = {
    requests.addOne((request, request.getHeaders))
    responseDefinition
  }

  override def getName: String = "WiremockResponseDefinitionTransformer"

  def reset(): Unit = requests.clear()
}
