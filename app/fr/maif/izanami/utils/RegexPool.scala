package fr.maif.izanami.utils

import play.api.Logger

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

case class Regex(originalPattern: String, compiledPattern: Pattern) {
  def matches(value: String): Boolean   = compiledPattern.matcher(value).matches()
  def split(value: String): Seq[String] = compiledPattern.split(value).toSeq
}

object RegexPool {
  lazy val logger: Logger = Logger("izanami-regex-pool")

  private val pool = new ConcurrentHashMap[String, Regex]() // TODO: check growth over time

  def apply(originalPattern: String): Regex = {
    if (!pool.containsKey(originalPattern)) {
      val processedPattern: String = originalPattern.replace(".", "\\.").replaceAll("\\*", ".*")
      if (logger.isTraceEnabled) logger.trace(s"Compiling pattern : `$processedPattern`")
      pool.putIfAbsent(originalPattern, Regex(originalPattern, Pattern.compile(processedPattern)))
    }
    pool.get(originalPattern)
  }
}
