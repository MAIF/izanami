package fr.maif.izanami.datastores

import org.apache.pekko.actor.Cancellable
import buildinfo.BuildInfo
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.models.Tenant
import fr.maif.izanami.security.IdGenerator
import fr.maif.izanami.utils.Datastore
import io.vertx.sqlclient.{Row, SqlConnection}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt


class StatsDatastore(val env: Env) extends Datastore {
  var anonymousReportingCancellation: Cancellable = Cancellable.alreadyCancelled

  override def onStart(): Future[Unit] = {
    anonymousReportingCancellation = env.actorSystem.scheduler.scheduleAtFixedRate(0.minutes, 24.hours)(() =>
      env.datastores.configuration
        .readFullConfiguration()
        .foreach(conf => {
          if (conf.anonymousReporting) {
            sendAnonymousReporting()
          } else {}
        })
    )
    Future.successful(())
  }

  override def onStop(): Future[Unit] = {
    anonymousReportingCancellation.cancel()
    Future.successful(())
  }

  def sendAnonymousReporting(): Future[Unit] = {
    retrieveStats()
      .flatMap(json => {
        env.Ws.url(env.typedConfiguration.reporting.url.toString).post(json)
      })
      .map(_ => ())
  }

  def retrieveStats(): Future[JsValue] = {
    env.postgresql
      .executeInTransaction(conn => {
        env.postgresql
          .queryAll(s"""
             |SELECT name FROM izanami.tenants
             |""".stripMargin) { r => r.optString("name") }
          .flatMap(names =>
            Future.sequence(names.map(name => {
              retrieveTenantStats(name, conn)
            }))
          )
          .map(l => l.foldLeft(TenantStats())((s1, s2) => s1.mergeWith(s2)))
      })
      .flatMap(stats => {
        retrieveRunInformations().map(runInfo => runInfo ++ Json.obj("entities" -> stats.toJson))
      })
      .flatMap(json => {
        readMailerType()
          .flatMap(mailerInfo => {
            readIntegrationInformations()
              .map(integrationInformations => {
                val features = mailerInfo ++ integrationInformations
                json ++ Json.obj("features" -> features)
              })
          })
      })
      .map(json => {
        json ++ Json.obj("stats" -> Json.obj(), "tenants" -> Json.arr(), "containerized" -> isContainerized)
      })
  }

  def isContainerized: Boolean = env.typedConfiguration.containerized

  def retrieveRunInformations(): Future[JsObject] = {
    val now = Instant.now()
    for (izanamiId <- env.datastores.configuration.readId())
      yield Json.obj(
        "os"                 -> Json.obj(
          "name"    -> System.getProperty("os.name"),
          "arch"    -> System.getProperty("os.arch"),
          "version" -> System.getProperty("os.version")
        ),
        "izanami_version"    -> BuildInfo.version,
        "java_version"       -> Json.obj(
          "version" -> System.getProperty("java.version"),
          "vendor"  -> System.getProperty("java.vendor")
        ),
        "@id"                -> IdGenerator.uuid,
        "izanami_cluster_id" -> izanamiId,
        "@timestamp"         -> now.toEpochMilli,
        "timestamp_str"      -> now.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      )
  }

  case class TenantStats(
      classicalFeaturesCount: Int = 0,
      scriptFeaturesCount: Int = 0,
      classicalOverloadCount: Int = 0,
      scriptOverloadCount: Int = 0,
      projectCount: Int = 0,
      adminKeyCount: Int = 0,
      nonAdminKeyCount: Int = 0,
      adminUserCount: Int = 0,
      nonAdminUserCount: Int = 0,
      tagCount: Int = 0
  ) {
    def mergeWith(other: TenantStats): TenantStats = {
      copy(
        classicalFeaturesCount = classicalFeaturesCount + other.classicalFeaturesCount,
        scriptFeaturesCount = scriptFeaturesCount + other.scriptFeaturesCount,
        classicalOverloadCount = classicalOverloadCount + other.classicalOverloadCount,
        scriptOverloadCount = scriptOverloadCount + other.scriptOverloadCount,
        projectCount = projectCount + other.projectCount,
        adminKeyCount = adminKeyCount + other.adminKeyCount,
        nonAdminKeyCount = nonAdminKeyCount + other.nonAdminKeyCount,
        adminUserCount = adminUserCount + other.adminUserCount,
        nonAdminUserCount = nonAdminUserCount + other.nonAdminUserCount,
        tagCount = tagCount + other.tagCount
      )
    }

    def toJson: JsObject = {
      Json.obj(
        "classicalFeaturesCount" -> classicalFeaturesCount,
        "scriptFeaturesCount"    -> scriptFeaturesCount,
        "classicalOverloadCount" -> classicalOverloadCount,
        "scriptOverloadCount"    -> scriptOverloadCount,
        "projectCount"           -> projectCount,
        "adminKeyCount"          -> adminKeyCount,
        "nonAdminKeyCount"       -> nonAdminKeyCount,
        "adminUserCount"         -> adminUserCount,
        "nonAdminUserCount"      -> nonAdminUserCount,
        "tagCount"               -> tagCount
      )
    }
  }

  def retrieveTenantStats(tenant: String, conn: SqlConnection): Future[TenantStats] = {
    Tenant.isTenantValid(tenant)
    env.postgresql
      .queryRaw(
        s"""
         |select count(id), script_config is null as classical from "${tenant}".features group by classical
         |""".stripMargin,
        conn = Some(conn)
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
             |select count(*), script_config is null as classical from "${tenant}".feature_contexts_strategies group by classical
             |""".stripMargin,
              conn = Some(conn)
            ) { rows =>
              {
                readBooleanCount(rows, "classical")
              }
            }
            .map { case (classicalOverloadCount, scriptOverloadCount) =>
              TenantStats(
                classicalFeaturesCount = classicalFeaturesCount,
                scriptFeaturesCount = scriptFeaturesCount,
                classicalOverloadCount = classicalOverloadCount,
                scriptOverloadCount = scriptOverloadCount
              )
            }
        }
      }
      .flatMap(stats => {
        env.postgresql
          .queryOne(
            s"""
             |select count(*) from "${tenant}".projects
             |""".stripMargin,
            conn = Some(conn)
          ) { r => r.optInt("count") }
          .map(o => o.getOrElse(0))
          .map(projectCount => stats.copy(projectCount = projectCount))
      })
      .flatMap(stats => {
        env.postgresql
          .queryRaw(
            s"""
           |select count(*), admin from "${tenant}".apikeys group by admin
           |""".stripMargin,
            conn = Some(conn)
          ) { rows => readBooleanCount(rows, "admin") }
          .map { case (admin, nonAdmin) => stats.copy(adminKeyCount = admin, nonAdminKeyCount = nonAdmin) }
      })
      .flatMap(stats => {
        env.postgresql
          .queryRaw(
            s"""
               |select count(*), admin from izanami.users group by admin
               |""".stripMargin,
            conn = Some(conn)
          ) { rows => readBooleanCount(rows, "admin") }
          .map { case (admin, nonAdmin) => stats.copy(adminUserCount = admin, nonAdminUserCount = nonAdmin) }
      })
      .flatMap(stats => {
        env.postgresql
          .queryOne(
            s"""
               |select count(*) from "${tenant}".tags
               |""".stripMargin,
            conn = Some(conn)
          ) { r => r.optInt("count") }
          .map(o => o.getOrElse(0))
          .map(projectCount => stats.copy(projectCount = projectCount))
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

  def readIntegrationInformations(): Future[JsObject] = {
    val isWasmPresent = env.datastores.configuration.readWasmConfiguration().isDefined
    env.datastores.configuration
      .readFullConfiguration()
      .fold(
        err =>
          Json.obj(
            "wasmo" -> isWasmPresent,
            "oidc"  -> false
          ),
        config =>
          (
            Json.obj(
              "wasmo" -> isWasmPresent,
              "oidc"  -> config.oidcConfiguration.isDefined
            )
          )
      )
  }

  def readMailerType(): Future[JsObject] = {
    env.datastores.configuration
      .readFullConfiguration()
      .fold(
        err => Json.obj(),
        c =>
          Json.obj(
            "mailer"          -> c.mailConfiguration.mailerType.toString,
            "invitation_mode" -> c.invitationMode.toString
          )
      )
  }
}
