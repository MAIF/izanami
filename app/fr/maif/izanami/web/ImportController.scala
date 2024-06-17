package fr.maif.izanami.web

import akka.util.ByteString
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.{AbstractFeature, ApiKey, CompleteFeature, CompleteWasmFeature, Feature, RightLevels, UserWithRights}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.OldKey.{oldKeyReads, toNewKey}
import fr.maif.izanami.v1.OldScripts.doesUseHttp
import fr.maif.izanami.v1.OldUsers.{oldUserReads, toNewUser}
import fr.maif.izanami.v1.{JavaScript, OldFeature, OldGlobalScript, WasmManagerClient}
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.web.ImportController.{extractProjectAndName, parseStrategy, readFile, scriptIdToNodeCompatibleName, unnest}
import fr.maif.izanami.web.ImportState.importResultWrites
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind.{Base64, Wasmo}
import play.api.libs.Files
import play.api.libs.json._
import play.api.mvc._

import java.net.URI
import java.time.ZoneId
import java.util.UUID
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Right, Try}

sealed trait ImportStatus
case object Pending extends ImportStatus {
  override def toString: String = this.productPrefix
}
case object Success extends ImportStatus {
  override def toString: String = this.productPrefix
}
case object Failed extends ImportStatus {
  override def toString: String = this.productPrefix
}

sealed trait ImportState {
  def id: UUID
  def status: ImportStatus
}
sealed trait ImportResult extends ImportState
case class ImportSuccess(id: UUID, features: Int, users: Int, scripts: Int, keys: Int, incompatibleScripts: Seq[String] = Seq()) extends ImportResult {
  val status = Success
}
case class ImportFailure(id: UUID, errors: Seq[String]) extends ImportResult {
  val status = Failed
}
case class ImportPending(id: UUID) extends ImportState {
  val status = Pending
}

object ImportState {

  val importSuccessReads: Reads[ImportSuccess] = json => {
    for {
      id <- (json \ "id").asOpt[UUID]
      features <- (json \ "features").asOpt[Int]
      scripts <- (json \ "scripts").asOpt[Int]
      keys <- (json \ "keys").asOpt[Int]
      users <- (json \ "users").asOpt[Int]
      incompatibleScripts <- (json \ "incompatibleScripts").asOpt[Seq[String]]
    } yield ImportSuccess(id=id, features=features,scripts=scripts,keys=keys,users=users,incompatibleScripts=incompatibleScripts)
  }.map(JsSuccess(_)).getOrElse(JsError("Failed to read import success"))

  val importFailureReads: Reads[ImportFailure] = json => {
    for {
      id <- (json \ "id").asOpt[UUID]
      errors <- (json \ "errors").asOpt[Seq[String]]
    } yield ImportFailure(id = id, errors=errors)
  }.map(JsSuccess(_)).getOrElse(JsError("Failed to read import failure"))

  val importPendingReads: Reads[ImportPending] = json => {
    for {
      id <- (json \ "id").asOpt[UUID]
    } yield ImportPending(id = id)
  }.map(JsSuccess(_)).getOrElse(JsError("Failed to read import pending"))

  val importSuccessWrites: Writes[ImportSuccess] = s => {
    Json.obj(
      "status" -> s.status.toString,
      "id" -> s.id,
      "features" -> s.features,
      "keys" -> s.keys,
      "scripts" -> s.scripts,
      "users" -> s.users,
      "incompatibleScripts" -> s.incompatibleScripts
    )
  }

  val importFailureWrites: Writes[ImportFailure] = s => {
    Json.obj(
      "status" -> s.status.toString,
      "id" -> s.id,
      "errors" -> s.errors
    )
  }

  val importPendingWrites: Writes[ImportPending] = s => {
    Json.obj(
      "status" -> s.status.toString,
      "id" -> s.id
    )
  }

  val importResultWrites: Writes[ImportState] = {
    case s@ImportSuccess(id, features, users, scripts, keys, incompatibleScripts) => importSuccessWrites.writes(s)
    case f@ImportFailure(id, errors) => importFailureWrites.writes(f)
    case p@ImportPending(id) => importPendingWrites.writes(p)
  }


  val importResultReads: Reads[ImportState] = json => {
    (json \ "status").asOpt[String].map(_.toUpperCase).map {
      case "SUCCESS" => importSuccessReads.reads(json)
      case "FAILED" => importFailureReads.reads(json)
      case "PENDING" => importPendingReads.reads(json)
      case _ => JsError("Can't find known status field")
    }.getOrElse(JsError("Can't find known status field"))
  }
}


class ImportController (val env: Env,
                        val controllerComponents: ControllerComponents,
                        val tenantAuthAction: TenantAuthActionFactory,
                        val wasmManagerClient: WasmManagerClient
                       ) extends BaseController {

  implicit val ec: ExecutionContext = env.executionContext;

  def deleteImportStatus(tenant: String, id: String): Action[AnyContent] = tenantAuthAction(tenant, RightLevels.Admin).async {
    implicit request => {
      env.datastores.tenants.deleteImportStatus(UUID.fromString(id)).map(_ => NoContent)
    }
  }

  def readImportStatus(tenant: String, id: String): Action[AnyContent] = tenantAuthAction(tenant, RightLevels.Admin).async {
    implicit request => {
      env.datastores.tenants.readImportStatus(UUID.fromString(id)).map {
        case Some(importResult) => Ok(Json.toJson(importResult)(importResultWrites))
        case None => NotFound(Json.obj("message" -> "Can't find this import"))
      }
    }
  }

  def importData(
                  tenant: String,
                  conflict: String,
                  timezone: String,
                  deduceProject: Boolean,
                  create: Option[Boolean],
                  project: Option[String],
                  projectPartSize: Option[Int],
                  inlineScript: Option[Boolean]
                ): Action[MultipartFormData[Files.TemporaryFile]] =
    tenantAuthAction(tenant, RightLevels.Admin).async(parse.multipartFormData) { implicit request =>
      val isBase64 = inlineScript.getOrElse(true)


      def runImport(id: UUID): Future[ImportResult] = {
        val strategy = parseStrategy(conflict)

        val files: Map[String, URI] = request.body.files.map(f => (f.key, f.ref.path.toUri)).toMap

        case class MigrationData(
                                  features: Seq[CompleteFeature] = Seq(),
                                  users: Seq[UserWithRights] = Seq(),
                                  keys: Seq[ApiKey] = Seq(),
                                  scripts: Seq[OldGlobalScript] = Seq(),
                                  excludedScripts: Seq[OldGlobalScript] = Seq()
                                )


        val projectChoiceStrategy: ProjectChoiceStrategy = if (!deduceProject) {
          // TODO handle missing project
          FixedProject(project.get)
        } else {
          DeduceProject(projectPartSize.getOrElse(1))
        }

        val maybeEitherScripts = files.get("scripts")
          .map(uri => unnest(readFile(uri, OldFeature.globalScriptReads).map(_.map(Right(_)))))
          .map(either => either.map(scripts => scripts.map(s => s.copy(id = scriptIdToNodeCompatibleName(s.id)))))

        val maybeEitherFeatures = files
          .get("features")
          .map(uri =>
            unnest(
              readFile(uri, OldFeature.oldFeatureReads).map(features => {
                val globalScriptById: Map[String, OldGlobalScript] = maybeEitherScripts
                  .flatMap(_.toOption.map(s => s.map(g => (g.id, g)).toMap)).getOrElse(Map())

                features.map(feature => {
                  extractProjectAndName(feature, projectChoiceStrategy)
                    .flatMap { case (project, name) =>
                      feature.toFeature(project, ZoneId.of(timezone), globalScriptById).map {
                        case (feature, maybeScript) => (feature.withName(name), maybeScript)
                      }
                    }
                })
              })
            )
          )

        val users = files.get("users")
          .map(uri => readFile(uri, oldUserReads))

        val keys = files.get("keys")
          .map(uri => readFile(uri, oldKeyReads))

        val errors = maybeEitherFeatures.map(e => e.swap.getOrElse(Seq())).toSeq.flatten

        val eitherData =
          if (Seq(maybeEitherFeatures, keys, users).forall(_.isEmpty)) {
            Left(Seq("No file provided, nothing to import"))
          } else if (errors.nonEmpty) {
            Left(errors)
          } else {
            val projects = if (deduceProject) {
              maybeEitherFeatures.flatMap(_.toOption.map(_.map(_._1.project))).getOrElse(Seq()).toSet
            } else {
              Set(project.get)
            }
            val maybeEitherUsers = users.map(t => unnest(t.map(users => users.map(u => toNewUser(tenant, u, projects, deduceProject)))))
            val maybeEitherKeys = keys.map(t => unnest(t.map(keys => keys.map(k => Right(toNewKey(tenant, k, projects, deduceProject))))))


            val errors = Seq(maybeEitherUsers, maybeEitherKeys, maybeEitherScripts).foldLeft(Seq[String]()) {
              case (s, Some(Left(errors))) => s.concat(errors)
              case (s, _) => s
            }

            val oldScripts = maybeEitherFeatures.flatMap(_.toOption).toSeq.flatten.flatMap {
              case (_, None) => None
              case (feature, Some(s)) => {
                val id = s"${feature.id}_script"
                Some(OldGlobalScript(id = id, name = id, source = s, description = None))
              }
            }.concat(maybeEitherScripts.map(_.getOrElse(Seq())).getOrElse(Seq()))

            val (compatibleScripts, incompatibleScript) = oldScripts.partition(s => {
              val isLangageSupported = s.source.language == JavaScript
              val hasJsHttpCall = if(s.source.language == JavaScript) {
                doesUseHttp(s.source.script)
              } else {
                false
              }
              isLangageSupported && !hasJsHttpCall
            })


            if (errors.nonEmpty) {
              Left(errors)
            } else {
              Right(MigrationData(
                features = maybeEitherFeatures
                  .flatMap(either => either.toOption).toSeq
                  .flatMap(s => s.map(tuple => tuple._1))
                  .map {
                    case CompleteWasmFeature(id, name, project, enabled, WasmConfig(scriptName, _, _, _, _, _, _, _, _, _, _, _), tags, metadata, description)
                    if !compatibleScripts.exists(s => s.id == scriptName) => Feature(id=id, name=name, project=project, enabled=enabled, tags=tags, metadata=metadata, description=description, conditions=Set())
                    case f@_ => f
                  },
                keys = maybeEitherKeys.map(_.getOrElse(Seq())).getOrElse(Seq()),
                users = maybeEitherUsers.map(_.getOrElse(Seq())).getOrElse(Seq()),
                scripts = compatibleScripts,
                excludedScripts = incompatibleScript
              ))
            }
          }

        (strategy, eitherData) match {
          case (None, _) => ImportFailure(id, Seq("Unknown conflict handling strategy")).future
          case (_, Left(errors)) => ImportFailure(id, errors).future
          case (Some(conflictStrategy), Right(MigrationData(features, users, keys, scripts, excludedScripts))) => {
            env.postgresql.executeInTransaction(conn => {
              scripts
                .foldLeft(Future.successful[Either[IzanamiError, Map[String, (String, ByteString)]]](Right(Map())))((f, script) => {
                  f.flatMap {
                    case Right(ids) => wasmManagerClient.transferLegacyJsScript(script.id, script.source.script, local=isBase64).map {
                      case Left(error) => Left(error)
                      case Right((id, wasm)) => Right(ids + (script.id -> (id, wasm)))
                    }
                    case left => Future.successful(left)
                  }
                }).flatMap {
                case Left(err) => ImportFailure(id, Seq(err.message)).future
                case Right(scriptIds) => {
                  val featureWithCorrectPath = features.map {
                    case f@CompleteWasmFeature(id, name, project, enabled, w@WasmConfig(
                    scriptName,
                    source,
                    memoryPages,
                    functionName,
                    config,
                    allowedHosts,
                    allowedPaths,
                    wasi,
                    opa,
                    instances,
                    killOptions,
                    authorizations
                    ), tags, metadata, description) => f.copy(wasmConfig = w.copy(source = w.source.copy(kind=if(isBase64) Base64 else Wasmo, path = if(isBase64) java.util.Base64.getEncoder.encodeToString(scriptIds(scriptName)._2.toArray) else scriptIds(scriptName)._1)))
                    case f => f
                  }

                  env.datastores.features
                    .createFeaturesAndProjects(tenant, featureWithCorrectPath, conflictStrategy, user=request.user, conn = Some(conn))
                    .flatMap {
                      case Left(errors) => Left(errors).future
                      case Right(_) =>
                        env.datastores.apiKeys
                          .createApiKeys(tenant, keys, request.user, conflictStrategy, conn.some)
                    }
                    .flatMap {
                      case Left(err) => ImportFailure(id, err.map(err => err.message)).future
                      case Right(_) =>
                        env.datastores.users
                          .createUserWithConn(users, conn, conflictStrategy)
                          .map {
                            case Left(error) => ImportFailure(id, Seq(error.message))
                            case Right(_) => ImportSuccess(
                              id = id,
                              features = features.size,
                              users = users.size,
                              scripts = scripts.size,
                              keys = keys.size,
                              incompatibleScripts = excludedScripts.map(s => s.id)
                            )
                          }
                    }

                }
              }
            })
          }
        }
      }

      env.datastores.tenants.markImportAsStarted()
        .map {
          case Left(err) => err.toHttpResponse
          case Right(id) => {
            Future {
              runImport(id)
                .map {
                  case s@ImportSuccess(id, features, users, scripts, keys, incompatibleScripts) => env.datastores.tenants.markImportAsSucceded(id, s)
                  case f@ImportFailure(id, errors) => env.datastores.tenants.markImportAsFailed(id, f)
                }.recover(t => {
                  env.datastores.tenants.markImportAsFailed(id, ImportFailure(id, Seq(t.getMessage)))
                })
            }
            Accepted(Json.obj("id" -> id.toString))
          }
        }
    }
}

object ImportController {
  sealed trait ImportConflictStrategy
  case object Fail extends ImportConflictStrategy
  case object MergeOverwrite extends ImportConflictStrategy
  case object Skip extends ImportConflictStrategy

  def sequence[A, B](seq: Seq[Either[A, B]]): Either[A, Seq[B]] = seq.foldRight(Right(Nil): Either[A, List[B]]) {
    (e, acc) => for (xs <- acc; x <- e) yield x :: xs
  }

  def scriptIdToNodeCompatibleName(name: String): String = {
    name.replace("[", "-")
      .replace("~", "-")
      .replace(")", "-")
      .replace("(", "-")
      .replace("'", "-")
      .replace("!", "-")
      .replace("*", "-")
      .replace(" ", "-")
      .replace("]", "-")
      .replace(":", "-")
      .toLowerCase
    match {
      case str if str.startsWith("\\.") => str.replaceFirst("\\.", "")
      case str if str.startsWith("_") => str.replaceFirst("_", "")
      case str => str
    }
  }

  private def readFile[T](uri: URI, reads: Reads[T]): Try[Seq[T]] = {
    Try(Source.fromFile(uri))
      .map(bf =>
        bf.getLines()
          .map(line => {
            val result = Json.parse(line).as[T](reads)
            result
          })
          .toSeq
      ).recover(ex => {
      throw ex
    })
  }

  def parseStrategy(str: String): Option[ImportConflictStrategy] = {
    Option(str).map(_.toUpperCase).flatMap {
      case "OVERWRITE" => Some(MergeOverwrite)
      case "SKIP" => Some(Skip)
      case "FAIL" => Some(Fail)
      case _ => None
    }
  }

  def extractProjectAndName(oldFeature: OldFeature, strategy: ProjectChoiceStrategy): Either[String, (String, String)] = {
    strategy match {
      case FixedProject(project) => Right((project, oldFeature.id))
      case DeduceProject(fieldCount) => {
        val id = oldFeature.id
        val parts = id.split(":")
        if (parts.length <= fieldCount) {
          Left(s"Feature ${id} is too short to exclude its first ${fieldCount} parts as project name")
        } else {
          (Right(parts.take(fieldCount).mkString(":"), parts.drop(fieldCount).mkString(":")))
        }
      }
    }
  }

  def unnest[R](value: Try[Seq[Either[String, R]]]): Either[Seq[String], Seq[R]] = {
    value.toEither.left
      .map(t => Seq("Failed to read file"))
      .flatMap(eithers => {
        eithers.foldLeft(Right(Seq()): Either[Seq[String], Seq[R]])((either, next) => {
          (either, next) match {
            case (Left(errors), Left(other)) => Left(errors.appended(other))
            case (Left(errors), _) => Left(errors)
            case (Right(rs), Right(r)) => Right(rs.appended(r))
            case (Right(_), Left(r)) => Left(Seq(r))
          }
        })
      })
  }
}
