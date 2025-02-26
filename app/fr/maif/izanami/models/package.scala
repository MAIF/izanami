package fr.maif.izanami

import scala.util.matching.Regex

package object models {
  val NAME_REGEXP: Regex = "^[a-zA-Z0-9_-]+$".r
  val USERNAME_REGEXP: Regex = "^[a-zA-Z0-9_\\-!#$%&'*+-/=?^`{|}~@.]+$".r
}
