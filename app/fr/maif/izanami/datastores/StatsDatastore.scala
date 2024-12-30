package fr.maif.izanami.datastores

import akka.actor.Cancellable
import buildinfo.BuildInfo
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.models.IzanamiConfiguration
import fr.maif.izanami.security.IdGenerator
import fr.maif.izanami.utils.Datastore
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class StatsDatastore(val env: Env) extends Datastore {
  var anonymousReportingCancellation: Cancellable    = Cancellable.alreadyCancelled

  override def onStart(): Future[Unit] = {
    anonymousReportingCancellation = env.actorSystem.scheduler.scheduleAtFixedRate(0.minutes, 24.hours)(() =>
      env.datastores.configuration.readConfiguration().foreach {
        case Right(conf) if conf.anonymousReporting => {
          sendAnonymousReporting()
        }
        case _ =>
      }
    )
    Future.successful(())
  }


  override def onStop(): Future[Unit] = {
    anonymousReportingCancellation.cancel()
    Future.successful(())
  }

  def sendAnonymousReporting(): Future[Unit] = {
    retrieveStats().flatMap(json => {
      env.Ws.url(env.configuration.get[String]("app.reporting.url")).post(json)
    }).map(_ => ())
  }

  def retrieveStats(): Future[JsValue] = {
    env.postgresql.executeInTransaction(conn => {
      env.postgresql
        .queryAll(s"""
             |SELECT name FROM izanami.tenants
             |""".stripMargin) { r => r.optString("name") }
        .flatMap(names =>
          Future.sequence(names.map(name => {
          retrieveTenantStats(name, conn)
        }))).map(l => l.foldLeft(TenantStats())((s1, s2) => s1.mergeWith(s2)))
    }).flatMap(stats => {
      retrieveRunInformations().map(runInfo => runInfo ++ Json.obj("entities" -> stats.toJson))
    }).flatMap(json => {
      readMailerType().map(mailerInfo => {
        val features = mailerInfo ++ readIntegrationInformations()
        json ++ Json.obj("features" -> features)
      })
    }).map(json => {
      json ++ Json.obj("stats" -> Json.obj(), "tenants" -> Json.arr(), "containerized" -> isContainerized)
    })
  }

  def isContainerized: Boolean = env.configuration.get[Boolean]("app.containerized")

  def retrieveRunInformations(): Future[JsObject] = {
    val now = Instant.now()
    for(
      izanamiId <- env.datastores.configuration.readId()
    ) yield Json.obj(
      "os" -> Json.obj("name" -> System.getProperty("os.name"), "arch" -> System.getProperty("os.arch"), "version" -> System.getProperty("os.version")),
      "izanami_version" -> BuildInfo.version,
      "java_version" -> Json.obj(
        "version" -> System.getProperty("java.version"),
        "vendor" -> System.getProperty("java.vendor")
      ),
      "@id" -> IdGenerator.uuid,
      "izanami_cluster_id" -> izanamiId,
      "@timestamp" -> now.toEpochMilli,
      "timestamp_str" -> now.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    )
  }

  case class TenantStats(
      classicalFeaturesCount: Int=0,
      scriptFeaturesCount: Int=0,
      classicalOverloadCount: Int=0,
      scriptOverloadCount: Int=0,
      projectCount: Int=0,
      adminKeyCount: Int=0,
      nonAdminKeyCount: Int=0,
      adminUserCount: Int=0,
      nonAdminUserCount: Int=0,
      tagCount: Int=0
  ) {
    def mergeWith(other: TenantStats): TenantStats = {
      copy(
      classicalFeaturesCount=classicalFeaturesCount + other.classicalFeaturesCount,
      scriptFeaturesCount=scriptFeaturesCount+other.scriptFeaturesCount,
      classicalOverloadCount=classicalOverloadCount+other.classicalOverloadCount,
      scriptOverloadCount=scriptOverloadCount+other.scriptOverloadCount,
      projectCount=projectCount+other.projectCount,
      adminKeyCount=adminKeyCount+other.adminKeyCount,
      nonAdminKeyCount=nonAdminKeyCount+other.nonAdminKeyCount,
      adminUserCount=adminUserCount+other.adminUserCount,
      nonAdminUserCount=nonAdminUserCount+other.nonAdminUserCount,
      tagCount=tagCount+other.tagCount
      )
    }

    def toJson: JsObject = {
      Json.obj(
        "classicalFeaturesCount" -> classicalFeaturesCount,
        "scriptFeaturesCount" -> scriptFeaturesCount,
        "classicalOverloadCount" -> classicalOverloadCount,
        "scriptOverloadCount" -> scriptOverloadCount,
        "projectCount" -> projectCount,
        "adminKeyCount" -> adminKeyCount,
        "nonAdminKeyCount" -> nonAdminKeyCount,
        "adminUserCount" -> adminUserCount,
        "nonAdminUserCount" -> nonAdminUserCount,
        "tagCount" -> tagCount
      )
    }
  }

  def retrieveTenantStats(tenant: String, conn: SqlConnection): Future[TenantStats] = {
    env.postgresql
      .queryRaw(
        s"""
         |select count(id), script_config is null as classical from features group by classical
         |""".stripMargin,
        schemas = Set(tenant),
        conn=Some(conn)
      ) { rows =>
        {
          readBooleanCount(rows, "classical")
        }
      }
      .flatMap {
        case (classicalFeaturesCount, scriptFeaturesCount) => {
          env.postgresql
            .queryRaw(
              s"""
             |select count(*), script_config is null as classical from feature_contexts_strategies group by classical
             |""".stripMargin,
              schemas = Set(tenant),
              conn=Some(conn)
            ) { rows =>
              {
                readBooleanCount(rows, "classical")
              }
            }
            .map { case (classicalOverloadCount, scriptOverloadCount) =>
              TenantStats(
                classicalFeaturesCount=classicalFeaturesCount,
                scriptFeaturesCount=scriptFeaturesCount,
                classicalOverloadCount=classicalOverloadCount,
                scriptOverloadCount=scriptOverloadCount
              )
            }
        }
      }
      .flatMap(stats => {
        env.postgresql
          .queryOne(
            s"""
             |select count(*) from projects
             |""".stripMargin,
            schemas = Set(tenant),
            conn=Some(conn)
          ) { r => r.optInt("count") }
          .map(o => o.getOrElse(0))
          .map(projectCount => stats.copy(projectCount=projectCount))
      })
      .flatMap(stats => {
        env.postgresql
          .queryRaw(
            s"""
           |select count(*), admin from apikeys group by admin
           |""".stripMargin,
            schemas = Set(tenant),
            conn=Some(conn)
          ) { rows => readBooleanCount(rows, "admin") }
          .map { case (admin, nonAdmin) => stats.copy(adminKeyCount=admin, nonAdminKeyCount=nonAdmin) }
      })
      .flatMap(stats => {
        env.postgresql
          .queryRaw(
            s"""
               |select count(*), admin from izanami.users group by admin
               |""".stripMargin,
            conn=Some(conn)
          ) { rows => readBooleanCount(rows, "admin") }
          .map { case (admin, nonAdmin) => stats.copy(adminUserCount=admin, nonAdminUserCount=nonAdmin) }
      }).flatMap(stats => {
        env.postgresql
          .queryOne(
            s"""
               |select count(*) from tags
               |""".stripMargin,
            schemas = Set(tenant),
            conn=Some(conn)
          ) { r => r.optInt("count") }
          .map(o => o.getOrElse(0))
          .map(projectCount => stats.copy(projectCount=projectCount))
      })
  }

  private def readBooleanCount(rows: List[Row], booleanColumnName: String): (Int, Int) = {
    rows
      .map(r => for (count <- r.optInt("count"); classical <- r.optBoolean(booleanColumnName)) yield (count, classical))
      .filter(_.isDefined)
      .map(_.get)
      .foldLeft((0, 0): (Int, Int)) {
        case ((_, second), (count, true)) => (count, second)
        case ((first, _), (count, false)) => (first, count)
      }
  }

  def readIntegrationInformations(): JsObject = {
    val isWasmPresent = env.datastores.configuration.readWasmConfiguration().isDefined
    val isOidcPresent = env.datastores.configuration.readOIDCConfiguration().isDefined

    Json.obj(
      "wasmo" -> isWasmPresent,
      "oidc" -> isOidcPresent
    )
  }

  def readMailerType(): Future[JsObject] = {
    env.datastores.configuration.readConfiguration().map {
      case Left(err) => Json.obj()
      case Right(IzanamiConfiguration(mailer, invitationMode, _, _, _)) => Json.obj(
        "mailer" -> mailer.toString,
        "invitation_mode" -> invitationMode.toString
      )
    }
  }
}
