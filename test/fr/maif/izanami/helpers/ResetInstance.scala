package fr.maif.izanami.helpers

import fr.maif.izanami.api.BaseAPISpec.{TestUser, _}

import java.time.{DayOfWeek, LocalDateTime, LocalTime}

object ResetInstance {
  def main(args: Array[String]): Unit = {
    cleanUpDB(hard = true)
    System.exit(0)
  }
}
