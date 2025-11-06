package fr.maif.izanami.utils

sealed abstract class Done
case object Done extends Done {
  def getInstance(): Done = this
  def done(): Done = this
}
