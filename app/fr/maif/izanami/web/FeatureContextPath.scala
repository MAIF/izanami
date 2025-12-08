package fr.maif.izanami.web

import play.api.mvc.{PathBindable, QueryStringBindable}

case class FeatureContextPath(elements: Seq[String] = Seq()) {
  def toUserPath: String = elements.mkString("/")
  def toDBPath: String = elements.mkString(".")
  def isAscendantOf(other: FeatureContextPath): Boolean = {
    other.elements.startsWith(elements)
  }
  def isDescendantOf(other: FeatureContextPath): Boolean = {
    elements.startsWith(other.elements)
  }
  def isEmpty: Boolean = elements.isEmpty

  def append(name: String): FeatureContextPath = FeatureContextPath(elements.appended(name))
}

object FeatureContextPath {
  def fromDBString(dbString: String):FeatureContextPath = {
    FeatureContextPath(dbString.split("\\.").toSeq)
  }

  def fromUserString(userString: String): FeatureContextPath = {
    FeatureContextPath(userString.split("/").toSeq.filter(str => str.nonEmpty))
  }

  implicit def pathBinder(implicit
      strBinder: PathBindable[String]
  ): PathBindable[FeatureContextPath] =
    new PathBindable[FeatureContextPath] {
      override def bind(
          key: String,
          value: String
      ): Either[String, FeatureContextPath] = {
        strBinder
          .bind(key, value)
          .map(str => {
            FeatureContextPath(str.split("/").toSeq.filter(s => s.nonEmpty))
          })
      }
      override def unbind(key: String, path: FeatureContextPath): String = {
        path.elements.mkString("/")
      }
    }

  implicit def queryStringBindable(implicit
      seqBinder: QueryStringBindable[Seq[String]]
  ): QueryStringBindable[FeatureContextPath] =
    new QueryStringBindable[FeatureContextPath] {
      override def bind(
          key: String,
          params: Map[String, Seq[String]]
      ): Option[Either[String, FeatureContextPath]] = {
        for (eitherContext <- seqBinder.bind("context", params)) yield {
          Right(
            FeatureContextPath(elements =
              eitherContext
                .map(seq =>
                  seq
                    .filter(str => str.nonEmpty)
                    .flatMap(str =>
                      str.split("/").toSeq.filter(s => s.nonEmpty)
                    )
                )
                .getOrElse(Seq())
            )
          )
        }
      }

      override def unbind(key: String, request: FeatureContextPath): String = {
        throw new NotImplementedError("")
      }
    }
}
