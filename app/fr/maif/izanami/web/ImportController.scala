package fr.maif.izanami.web

import akka.util.ByteString
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{IzanamiError, PartialImportFailure}
import fr.maif.izanami.models.ExportedType.parseExportedType
import fr.maif.izanami.models.features.{BooleanResult, BooleanResultDescriptor}
import fr.maif.izanami.models._
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import fr.maif.izanami.v1.OldKey.{oldKeyReads, toNewKey}
import fr.maif.izanami.v1.OldScripts.doesUseHttp
import fr.maif.izanami.v1.OldUsers.{oldUserReads, toNewUser}
import fr.maif.izanami.v1.{JavaScript, OldFeature, OldGlobalScript, WasmManagerClient}
import fr.maif.izanami.wasm.WasmConfig
import fr.maif.izanami.web.ImportController._
import fr.maif.izanami.web.ImportState.importResultWrites
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind.{Base64, Wasmo}
import play.api.libs.Files
import play.api.libs.json._
import play.api.mvc._

import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
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
case object Failed  extends ImportStatus {
  override def toString: String = this.productPrefix
}

sealed trait ImportState {
  def id: UUID
  def status: ImportStatus
}
sealed trait ImportResult                               extends ImportState
case class ImportSuccess(
    id: UUID,
    features: Int,
    users: Int,
    scripts: Int,
    keys: Int,
    incompatibleScripts: Seq[String] = Seq()
)                                                       extends ImportResult {
  val status = Success
}
case class ImportFailure(id: UUID, errors: Seq[String]) extends ImportResult {
  val status = Failed
}
case class ImportPending(id: UUID)                      extends ImportState  {
  val status = Pending
}

object ImportState {

  val importSuccessReads: Reads[ImportSuccess] = json =>
    {
      for {
        id                  <- (json \ "id").asOpt[UUID]
        features            <- (json \ "features").asOpt[Int]
        scripts             <- (json \ "scripts").asOpt[Int]
        keys                <- (json \ "keys").asOpt[Int]
        users               <- (json \ "users").asOpt[Int]
        incompatibleScripts <- (json \ "incompatibleScripts").asOpt[Seq[String]]
      } yield ImportSuccess(
        id = id,
        features = features,
        scripts = scripts,
        keys = keys,
        users = users,
        incompatibleScripts = incompatibleScripts
      )
    }.map(JsSuccess(_)).getOrElse(JsError("Failed to read import success"))

  val importFailureReads: Reads[ImportFailure] = json =>
    {
      for {
        id     <- (json \ "id").asOpt[UUID]
        errors <- (json \ "errors").asOpt[Seq[String]]
      } yield ImportFailure(id = id, errors = errors)
    }.map(JsSuccess(_)).getOrElse(JsError("Failed to read import failure"))

  val importPendingReads: Reads[ImportPending] = json =>
    {
      for {
        id <- (json \ "id").asOpt[UUID]
      } yield ImportPending(id = id)
    }.map(JsSuccess(_)).getOrElse(JsError("Failed to read import pending"))

  val importSuccessWrites: Writes[ImportSuccess] = s => {
    Json.obj(
      "status"              -> s.status.toString,
      "id"                  -> s.id,
      "features"            -> s.features,
      "keys"                -> s.keys,
      "scripts"             -> s.scripts,
      "users"               -> s.users,
      "incompatibleScripts" -> s.incompatibleScripts
    )
  }

  val importFailureWrites: Writes[ImportFailure] = s => {
    Json.obj(
      "status" -> s.status.toString,
      "id"     -> s.id,
      "errors" -> s.errors
    )
  }

  val importPendingWrites: Writes[ImportPending] = s => {
    Json.obj(
      "status" -> s.status.toString,
      "id"     -> s.id
    )
  }

  val importResultWrites: Writes[ImportState] = {
    case s @ ImportSuccess(id, features, users, scripts, keys, incompatibleScripts) => importSuccessWrites.writes(s)
    case f @ ImportFailure(id, errors)                                              => importFailureWrites.writes(f)
    case p @ ImportPending(id)                                                      => importPendingWrites.writes(p)
  }

  val importResultReads: Reads[ImportState] = json => {
    (json \ "status")
      .asOpt[String]
      .map(_.toUpperCase)
      .map {
        case "SUCCESS" => importSuccessReads.reads(json)
        case "FAILED"  => importFailureReads.reads(json)
        case "PENDING" => importPendingReads.reads(json)
        case _         => JsError("Can't find known status field")
      }
      .getOrElse(JsError("Can't find known status field"))
  }
}

class ImportController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val tenantAuthAction: TenantAuthActionFactory,
    val wasmManagerClient: WasmManagerClient,
    val maybeTokenAuthAction: PersonnalAccessTokenTenantAuthActionFactory
) extends BaseController {

  implicit val ec: ExecutionContext = env.executionContext;

  def deleteImportStatus(tenant: String, id: String): Action[AnyContent] =
    maybeTokenAuthAction(tenant, RightLevel.Admin, Import).async { implicit request =>
      {
        env.datastores.tenants.deleteImportStatus(UUID.fromString(id)).map(_ => NoContent)
      }
    }

  def readImportStatus(tenant: String, id: String): Action[AnyContent] =
    maybeTokenAuthAction(tenant, RightLevel.Admin, Import).async { implicit request =>
      {
        env.datastores.tenants.readImportStatus(UUID.fromString(id)).map {
          case Some(importResult) => Ok(Json.toJson(importResult)(importResultWrites))
          case None               => NotFound(Json.obj("message" -> "Can't find this import"))
        }
      }
    }

  def importData(
      version: Int,
      tenant: String,
      conflict: String,
      timezone: Option[String],
      deduceProject: Boolean,
      create: Option[Boolean],
      project: Option[String],
      projectPartSize: Option[Int],
      inlineScript: Option[Boolean]
  ): Action[MultipartFormData[Files.TemporaryFile]] =
    maybeTokenAuthAction(tenant, RightLevel.Admin, Import).async(parse.multipartFormData) { implicit request =>
      if (version == 1) {
        timezone
          .map(tz => {
            importV1Data(
              request,
              tenant,
              conflict,
              tz,
              deduceProject,
              create,
              project,
              projectPartSize,
              inlineScript
            )
          })
          .getOrElse(BadRequest(Json.obj("message" -> "Missing timezone")).future)
      } else if (version == 2) {
        importV2Data(request, tenant, conflict)
      } else {
        BadRequest(Json.obj("message" -> s"Invalid version: $version")).future
      }
    }

  def importV2Data(
      request: UserNameRequest[MultipartFormData[Files.TemporaryFile]],
      tenant: String,
      conflict: String
  ): Future[Result] = {
    val files: Map[String, URI] = request.body.files.map(f => (f.key, f.ref.path.toUri)).toMap
    (for (
      conflictStrategy <- ImportController.parseStrategy(conflict);
      uri              <- files.get("export")
    ) yield {
      Try(scala.io.Source.fromFile(uri)).toEither.left
        .map(ex => {
          InternalServerError(Json.obj("message" -> "Failed to read file")).future
        })
        .map(bf => {
          bf.getLines()
            .flatMap(line => {
              (for (
                rowAsJson  <- Json.parse(line).asOpt[JsObject];
                dbRow      <- (rowAsJson \ "row").asOpt[JsObject];
                rowTypeStr <- (rowAsJson \ "_type").asOpt[String];
                rowType    <- parseExportedType(rowTypeStr)
              ) yield (rowType, dbRow)).toSeq
            })
            .toSeq
            .groupBy(_._1)
            .view
            .mapValues(v => v.map(_._2))
            .toMap
        })
        .map(m => fixImportDataIfNeeded(tenant, m))
        .map {
          case (messages, data) => {
            env.datastores.exportDatastore
              .importTenantData(tenant, data, conflictStrategy, request.user)
              .map {
                case Left(PartialImportFailure(failedElements)) =>
                  Conflict(
                    Json.obj(
                      "messages"  -> Json.toJson(messages),
                      "conflicts" -> Json.toJson(failedElements.values.flatten)
                    )
                  )
                case Right(_)                                   => Ok(Json.obj("messages" -> Json.toJson(messages)))
                case Left(err)                                  => err.toHttpResponse
              }
          }
        }
    }).toRight(Future.successful(BadRequest(Json.obj("message" -> "Missing export file")))).flatten.fold(r => r, r => r)
  }

  def fixImportDataIfNeeded(
      tenant: String,
      data: Map[ExportedType, Seq[JsObject]]
  ): (Seq[String], Map[ExportedType, Seq[JsObject]]) = {
  val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC"));
    var features  = data.getOrElse(FeatureType, Seq())
    features = features.map {
      case obj if !obj.keys.contains("result_type") => {
        val res: JsObject = obj + ("result_type" -> JsString(BooleanResult.toDatabaseName))
        res
      }
      case obj                                      => obj
    }.map {
      case obj if !obj.keys.contains("created_at") => {
        val res: JsObject = obj + ("created_at" -> JsString(formatter.format(Instant.now())))
        res
      }
      case obj                                      => obj
    }
    var overloads = data.getOrElse(OverloadType, Seq())
    overloads = overloads.map {
      case obj if !obj.keys.contains("result_type") => {
        val res: JsObject = obj + ("result_type" -> JsString(BooleanResult.toDatabaseName))
        res
      }
      case obj                                      => obj
    }
    overloads = overloads.map(json => castOldOverloadToNewOverloadIfNeeded(json))

    var keys     = data.getOrElse(KeyType, Seq())
    val messages = ArrayBuffer[String]()
    keys = keys.map(obj => {
      val clientId = (obj \ "clientid").as[String]
      if (!clientId.startsWith(s"${tenant}_")) {
        val name        = (obj \ "name").as[String]
        val parts       = clientId.split("_")
        val newClientId = s"${tenant}_${parts(1)}"

        messages.append(s"""New tenant name is different from old one (it changed from ${parts(0)} to $tenant).
                    Since api keys embed tenant name in their client id, client id for key $name has been udpated from $clientId to $newClientId.
                    You will need to update it in client applications.
                    """.stripMargin)
        (obj + ("clientid" -> JsString(newClientId))): JsObject
      } else {
        obj
      }
    })

    var globalContexts = data.getOrElse(GlobalContextType, Seq())
    globalContexts = globalContexts.map(json => {
      var id = (json \ "id").as[String]
      if (!id.startsWith(s"${tenant}_")) {
        val tail = id.split("_").toList.tail.mkString("_")
        id = s"${tenant}_" + tail
        json + ("id" -> JsString(id))
      } else {
        json
      }
    })

    val contexts = data.getOrElse(ContextType, Seq()).concat(
        data.getOrElse(LocalContextType, Seq())
        .map(json => oldContextToNewContext(LocalContextType, json))
      )
      .concat(data.getOrElse(GlobalContextType, Seq())
        .map(json => oldContextToNewContext(GlobalContextType, json))
      ).sortWith((json1, json2) => {
        val isFirstGlobal = (json1 \ "global").as[Boolean]
        val isSecondGlobal = (json2 \ "global").as[Boolean]

        if(isFirstGlobal && !isSecondGlobal) {
          true
        } else if(!isFirstGlobal && isSecondGlobal) {
          false
        } else {
          val firstParent = (json1 \ "parent").asOpt[String].filter(s => s!= null)
          val secondParent = (json2\ "parent").asOpt[String].filter(s => s!= null)

          (firstParent, secondParent) match {
            case (None, None) => true
            case (Some(_), None) => false
            case (None, Some(_)) => true
            case (Some(str1), Some(str2)) => str1.length < str2.length
          }
        }
      })

    (
      messages.toSeq,
      data + (FeatureType -> features, OverloadType -> overloads, KeyType -> keys, ContextType -> contexts) - LocalContextType - GlobalContextType
    )
  }

  def oldParentToNewParent(parent: String): String = {
    val parts = parent.split("_").toList.tail
    parts.mkString(".")
  }


  def castOldOverloadToNewOverloadIfNeeded(json: JsObject): JsObject = {
    val maybeOldContext = (json \ "local_context").asOpt[String].orElse((json \ "global_context").asOpt[String])

    maybeOldContext.fold(json)((oldCtx) => {
      val newCtx = oldParentToNewParent(oldCtx)

      val res: JsObject = json - "local_context" - "global_context" + ("context" -> JsString(newCtx))
      res
    })
  }

  def oldContextToNewContext(contextType: ExportedType, json: JsObject): JsObject = {
    if(contextType != GlobalContextType && contextType != LocalContextType) {
      throw new IllegalArgumentException(s"Failed to convert old context to new context : unknown type $contextType")
    }

    val global = if(contextType == GlobalContextType) true else false

    val parent = (json \ "parent").asOpt[String].orElse((json \ "global_parent").asOpt[String]).map(p => {
      val parts = p.split("_").toList.tail
      parts.mkString(".")
    }).orNull

    val maybeProject = (json \ "project").asOpt[String].filter(p => contextType == LocalContextType)

    val isProtected = (json \ "protected").asOpt[Boolean].getOrElse(false)

    Json.obj(
      "name" -> (json \ "name").as[String],
      "global" -> global,
      "parent" -> parent,
      "protected" -> isProtected
    ).applyOnWithOpt(maybeProject)((json, proj) => json + ("project" -> JsString(proj)))
  }

  def importV1Data(
      request: UserNameRequest[MultipartFormData[Files.TemporaryFile]],
      tenant: String,
      conflict: String,
      timezone: String,
      deduceProject: Boolean,
      create: Option[Boolean],
      project: Option[String],
      projectPartSize: Option[Int],
      inlineScript: Option[Boolean]
  ): Future[Result] = {
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

      val maybeEitherScripts = files
        .get("scripts")
        .map(uri => unnest(readFile(uri, OldFeature.globalScriptReads).map(_.map(Right(_)))))
        .map(either => either.map(scripts => scripts.map(s => s.copy(id = scriptIdToNodeCompatibleName(s.id)))))

      val maybeEitherFeatures = files
        .get("features")
        .map(uri =>
          unnest(
            readFile(uri, OldFeature.oldFeatureReads).map(features => {
              val globalScriptById: Map[String, OldGlobalScript] = maybeEitherScripts
                .flatMap(_.toOption.map(s => s.map(g => (g.id, g)).toMap))
                .getOrElse(Map())

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

      val users = files
        .get("users")
        .map(uri => readFile(uri, oldUserReads))

      val keys = files
        .get("keys")
        .map(uri => readFile(uri, oldKeyReads))

      val errors = maybeEitherFeatures.map(e => e.swap.getOrElse(Seq())).toSeq.flatten

      val eitherData =
        if (Seq(maybeEitherFeatures, keys, users).forall(_.isEmpty)) {
          Left(Seq("No file provided, nothing to import"))
        } else if (errors.nonEmpty) {
          Left(errors)
        } else {
          val projects         = if (deduceProject) {
            maybeEitherFeatures.flatMap(_.toOption.map(_.map(_._1.project))).getOrElse(Seq()).toSet
          } else {
            Set(project.get)
          }
          val maybeEitherUsers =
            users.map(t => unnest(t.map(users => users.map(u => toNewUser(tenant, u, projects, deduceProject)))))
          val maybeEitherKeys  =
            keys.map(t => unnest(t.map(keys => keys.map(k => Right(toNewKey(tenant, k, projects, deduceProject))))))

          val errors = Seq(maybeEitherUsers, maybeEitherKeys, maybeEitherScripts).foldLeft(Seq[String]()) {
            case (s, Some(Left(errors))) => s.concat(errors)
            case (s, _)                  => s
          }

          val oldScripts = maybeEitherFeatures
            .flatMap(_.toOption)
            .toSeq
            .flatten
            .flatMap {
              case (_, None)          => None
              case (feature, Some(s)) => {
                val id = s"${feature.id}_script"
                Some(OldGlobalScript(id = id, name = id, source = s, description = None))
              }
            }
            .concat(maybeEitherScripts.map(_.getOrElse(Seq())).getOrElse(Seq()))

          val (compatibleScripts, incompatibleScript) = oldScripts.partition(s => {
            val isLangageSupported = s.source.language == JavaScript
            val hasJsHttpCall      = if (s.source.language == JavaScript) {
              doesUseHttp(s.source.script)
            } else {
              false
            }
            isLangageSupported && !hasJsHttpCall
          })

          if (errors.nonEmpty) {
            Left(errors)
          } else {
            Right(
              MigrationData(
                features = maybeEitherFeatures
                  .flatMap(either => either.toOption)
                  .toSeq
                  .flatMap(s => s.map(tuple => tuple._1))
                  .map {
                    case CompleteWasmFeature(
                          id,
                          name,
                          project,
                          enabled,
                          WasmConfig(scriptName, _, _, _, _, _, _, _, _, _),
                          tags,
                          metadata,
                          description,
                          resultType
                        ) if !compatibleScripts.exists(s => s.id == scriptName) =>
                      Feature(
                        id = id,
                        name = name,
                        project = project,
                        enabled = enabled,
                        tags = tags,
                        metadata = metadata,
                        description = description,
                        resultDescriptor = BooleanResultDescriptor(
                          conditions = Seq()
                        )
                      )
                    case f @ _ => f
                  },
                keys = maybeEitherKeys.map(_.getOrElse(Seq())).getOrElse(Seq()),
                users = maybeEitherUsers.map(_.getOrElse(Seq())).getOrElse(Seq()),
                scripts = compatibleScripts,
                excludedScripts = incompatibleScript
              )
            )
          }
        }

      (strategy, eitherData) match {
        case (None, _)                                                                                       => ImportFailure(id, Seq("Unknown conflict handling strategy")).future
        case (_, Left(errors))                                                                               => ImportFailure(id, errors).future
        case (Some(conflictStrategy), Right(MigrationData(features, users, keys, scripts, excludedScripts))) => {
          env.postgresql.executeInTransaction(conn => {
            scripts
              .foldLeft(Future.successful[Either[IzanamiError, Map[String, (String, ByteString)]]](Right(Map())))(
                (f, script) => {
                  f.flatMap {
                    case Right(ids) =>
                      wasmManagerClient.transferLegacyJsScript(script.id, script.source.script, local = isBase64).map {
                        case Left(error)       => Left(error)
                        case Right((id, wasm)) => Right(ids + (script.id -> (id, wasm)))
                      }
                    case left       => Future.successful(left)
                  }
                }
              )
              .flatMap {
                case Left(err)        => ImportFailure(id, Seq(err.message)).future
                case Right(scriptIds) => {
                  val featureWithCorrectPath = features.map {
                    case f: CompleteWasmFeature =>
                      val wasmConfig = f.wasmConfig
                      f.copy(wasmConfig =
                        wasmConfig.copy(source =
                          wasmConfig.source.copy(
                            kind = if (isBase64) Base64 else Wasmo,
                            path =
                              if (isBase64)
                                java.util.Base64.getEncoder.encodeToString(scriptIds(wasmConfig.name)._2.toArray)
                              else scriptIds(wasmConfig.name)._1
                          )
                        )
                      )
                    case f                      => f
                  }

                  env.datastores.features
                    .createFeaturesAndProjects(
                      tenant,
                      featureWithCorrectPath,
                      conflictStrategy,
                      user = request.user,
                      conn = Some(conn)
                    )
                    .flatMap {
                      case Left(errors) => Left(errors).future
                      case Right(_)     =>
                        env.datastores.apiKeys
                          .createApiKeys(tenant, keys, request.user, conflictStrategy, conn.some)
                    }
                    .flatMap {
                      case Left(err) => ImportFailure(id, err.map(err => err.message)).future
                      case Right(_)  =>
                        env.datastores.users
                          .createUserWithConn(users, conn, conflictStrategy)
                          .map {
                            case Left(error) => ImportFailure(id, Seq(error.message))
                            case Right(_)    =>
                              ImportSuccess(
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

    if (request.body.files.isEmpty) {
      Future.successful(BadRequest(Json.obj("message" -> "No files provided")))
    } else {
      env.datastores.tenants
        .markImportAsStarted()
        .map {
          case Left(err) => err.toHttpResponse
          case Right(id) => {
            Future {
              runImport(id)
                .map {
                  case s @ ImportSuccess(id, features, users, scripts, keys, incompatibleScripts) =>
                    env.datastores.tenants.markImportAsSucceded(id, s)
                  case f @ ImportFailure(id, errors)                                              => env.datastores.tenants.markImportAsFailed(id, f)
                }
                .recover(t => {
                  env.datastores.tenants.markImportAsFailed(id, ImportFailure(id, Seq(t.getMessage)))
                })
            }
            Accepted(Json.obj("id" -> id.toString))
          }
        }
    }
  }
}

object ImportController {
  sealed trait ImportConflictStrategy
  case object Fail           extends ImportConflictStrategy
  case object MergeOverwrite extends ImportConflictStrategy
  case object Skip           extends ImportConflictStrategy

  def scriptIdToNodeCompatibleName(name: String): String = {
    name
      .replace("[", "-")
      .replace("~", "-")
      .replace(")", "-")
      .replace("(", "-")
      .replace("'", "-")
      .replace("!", "-")
      .replace("*", "-")
      .replace(" ", "-")
      .replace("]", "-")
      .replace(":", "-")
      .toLowerCase match {
      case str if str.startsWith("\\.") => str.replaceFirst("\\.", "")
      case str if str.startsWith("_")   => str.replaceFirst("_", "")
      case str                          => str
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
      )
      .recover(ex => {
        throw ex
      })
  }

  def parseStrategy(str: String): Option[ImportConflictStrategy] = {
    Option(str).map(_.toUpperCase).flatMap {
      case "OVERWRITE" => Some(MergeOverwrite)
      case "SKIP"      => Some(Skip)
      case "FAIL"      => Some(Fail)
      case _           => None
    }
  }

  def extractProjectAndName(
      oldFeature: OldFeature,
      strategy: ProjectChoiceStrategy
  ): Either[String, (String, String)] = {
    strategy match {
      case FixedProject(project)     => Right((project, oldFeature.id))
      case DeduceProject(fieldCount) => {
        val id    = oldFeature.id
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
            case (Left(errors), _)           => Left(errors)
            case (Right(rs), Right(r))       => Right(rs.appended(r))
            case (Right(_), Left(r))         => Left(Seq(r))
          }
        })
      })
  }
}
