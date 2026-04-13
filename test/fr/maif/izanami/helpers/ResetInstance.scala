package fr.maif.izanami.helpers

import fr.maif.izanami.api.BaseAPISpec.*

object ResetInstance {
  def main(args: Array[String]): Unit = {
    cleanUpDB(hard = true)
    System.exit(0)
  }
}
