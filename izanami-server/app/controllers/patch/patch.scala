package controllers.patch

import play.api.libs.json._

object Patch {

  implicit val jsPathFormat = Format[JsPath](
    __.read[String].map(_.split("/").filterNot(_.isEmpty).foldLeft[JsPath](JsPath)(_ \ _)),
    Writes[JsPath](path => JsString(path.toString()))
  )

  sealed trait Patch
  case class Add(path: JsPath, value: JsValue) extends Patch
  object Add {
    implicit val format = Json.format[Add]
  }
  case class Remove(path: JsPath) extends Patch
  object Remove {
    implicit val format = Json.format[Remove]
  }
  case class Replace(path: JsPath, value: JsValue) extends Patch
  object Replace {
    implicit val format = Json.format[Replace]
  }
  case class Copy(from: JsPath, path: JsPath) extends Patch
  object Copy {
    implicit val format = Json.format[Copy]
  }
  case class Move(from: JsPath, path: JsPath) extends Patch
  object Move {
    implicit val format = Json.format[Move]
  }
  case class Test(path: JsPath, value: JsValue) extends Patch
  object Test {
    implicit val format = Json.format[Test]
  }

  val reads: Reads[Patch] = Reads[Patch] {
    case obj: JsObject if (obj \ "op").as[String] == "add" =>
      Add.format.reads(obj)
    case obj: JsObject if (obj \ "op").as[String] == "remove" =>
      Remove.format.reads(obj)
    case obj: JsObject if (obj \ "op").as[String] == "replace" =>
      Replace.format.reads(obj)
    case obj: JsObject if (obj \ "op").as[String] == "copy" =>
      Copy.format.reads(obj)
    case obj: JsObject if (obj \ "op").as[String] == "move" =>
      Move.format.reads(obj)
    case obj: JsObject if (obj \ "op").as[String] == "test" =>
      Test.format.reads(obj)
    case other =>
      JsError("wrong format")
  }

  val writes: Writes[Patch] = Writes[Patch] {
    case p: Add     => Add.format.writes(p) ++ Json.obj("op"     -> classOf[Add].getSimpleName.toLowerCase())
    case p: Remove  => Remove.format.writes(p) ++ Json.obj("op"  -> classOf[Remove].getSimpleName.toLowerCase())
    case p: Replace => Replace.format.writes(p) ++ Json.obj("op" -> classOf[Replace].getSimpleName.toLowerCase())
    case p: Copy    => Copy.format.writes(p) ++ Json.obj("op"    -> classOf[Copy].getSimpleName.toLowerCase())
    case p: Move    => Move.format.writes(p) ++ Json.obj("op"    -> classOf[Move].getSimpleName.toLowerCase())
    case p: Test    => Test.format.writes(p) ++ Json.obj("op"    -> classOf[Test].getSimpleName.toLowerCase())
  }

  implicit val format = Format(reads, writes)

  def patch(patchs: Seq[Patch], value: JsObject): JsResult[JsObject] =
    patchs.foldLeft[JsResult[JsObject]](JsSuccess(value)) { (currentResult, patch) =>
      currentResult.flatMap(obj => patchOne(patch, obj))
    }

  def patchAs[T](patchs: Seq[Patch], value: T)(implicit reads: Reads[T], writes: Writes[T]): JsResult[T] =
    patch(patchs, writes.writes(value).as[JsObject]).flatMap(json => reads.reads(json))

  def patchOne(patch: Patch, toPatch: JsObject): JsResult[JsObject] =
    patch match {
      case Add(path, value) => JsSuccess(path.write[JsValue].writes(value).deepMerge(toPatch))
      case Remove(path)     => path.prune(toPatch)
      case Replace(path, value) =>
        patchOne(Remove(path), toPatch)
          .flatMap(res => patchOne(Add(path, value), res))
      case Copy(from, path) =>
        from.read[JsValue].reads(toPatch).map(js => path.write[JsValue].writes(js).deepMerge(toPatch))
      case Move(from, path) =>
        patchOne(Copy(from, path), toPatch)
          .flatMap(res => patchOne(Remove(from), res))
      case Test(path, value) =>
        path.read[JsValue].reads(toPatch).flatMap {
          case json if json == value => JsSuccess(toPatch)
          case json                  => JsError(s"$json at path $path doesn't match $value in $toPatch")
        }
    }

}
