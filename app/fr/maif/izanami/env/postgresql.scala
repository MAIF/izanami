package fr.maif.izanami.env

import org.apache.pekko.http.scaladsl.util.FastFuture
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import fr.maif.izanami.datastores.HashUtils
import fr.maif.izanami.env.PostgresqlErrors.{CHECK_VIOLATION, UNIQUE_VIOLATION}
import fr.maif.izanami.errors._
import fr.maif.izanami.security.IdGenerator
import fr.maif.izanami.utils.FutureEither
import fr.maif.izanami.utils.syntax.implicits.{BetterFutureEither, BetterSyntax}
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.{PemKeyCertOptions, PemTrustOptions}
import io.vertx.pgclient.{PgBuilder, PgConnectOptions, PgException, PgPool, SslMode}
import io.vertx.sqlclient.{PoolOptions, Row, RowSet, SqlConnection}
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.exception.FlywayValidateException
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.{Configuration, Logger}

import java.time.{Instant, LocalDateTime, OffsetDateTime, ZoneId}
import java.util.{Objects, UUID}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class Postgresql(env: Env) {

  import pgimplicits._

  import scala.jdk.CollectionConverters._

  private val logger = Logger("izanami")
  val pgConfiguration = env.typedConfiguration.pg
  val sslConfiguration        = pgConfiguration.ssl
  lazy val connectOptions      = if (pgConfiguration.uri.isDefined) {
    val uri = pgConfiguration.uri.get
    logger.info(s"Postgres URI : ${uri}")
    val opts = PgConnectOptions.fromUri(uri.toString)
    opts
  } else {

    val maybePgConfig = for(
      database <- pgConfiguration.database;
      // Retro compatibility : user became username
      user <- pgConfiguration.username.orElse(pgConfiguration.user)
    ) yield {

      val sslEnabled = sslConfiguration.enabled
      new PgConnectOptions()
        .applyOnWithOpt(pgConfiguration.connectTimeout)((p, v) => p.setConnectTimeout(v))
        .applyOnWithOpt(pgConfiguration.idleTimeout)((p, v) => p.setIdleTimeout(v))
        .applyOnWithOpt(pgConfiguration.logActivity)((p, v) => p.setLogActivity(v))
        .applyOnWithOpt(pgConfiguration.pipeliningLimit)((p, v) => p.setPipeliningLimit(v))
        .setPort(pgConfiguration.port)
        .setHost(pgConfiguration.host)
        .setDatabase(database)
        .setUser(user)
        .setPassword(pgConfiguration.password)
        .applyOnIf(sslEnabled) { pgopt =>
          pgopt.setSsl(true)
          val mode = SslMode.of(sslConfiguration.mode)
          val pemTrustOptions = new PemTrustOptions()
          val pemKeyCertOptions = new PemKeyCertOptions()
          pgopt.setSslMode(mode)

          pgopt.applyOnWithOpt(sslConfiguration.sslHandshakeTimeout)((p, v) => p.setSslHandshakeTimeout(v))
          sslConfiguration.trustedCertsPath match {
            case Nil => ()
            case pathes => {
              pathes.map(p => pemTrustOptions.addCertPath(p))
              pgopt.setPemTrustOptions(pemTrustOptions)
            }
          }
          sslConfiguration.trustedCertPath.map { path =>
            pemTrustOptions.addCertPath(path)
            pgopt.setPemTrustOptions(pemTrustOptions)
          }
          sslConfiguration.trustedCerts match {
            case Nil =>
            case certs => {
              certs.map(p => pemTrustOptions.addCertValue(Buffer.buffer(p)))
              pgopt.setPemTrustOptions(pemTrustOptions)
            }
          }
          sslConfiguration.trustedCert.map { path =>
            pemTrustOptions.addCertValue(Buffer.buffer(path))
            pgopt.setPemTrustOptions(pemTrustOptions)
          }
          sslConfiguration.clientCertsPath match{
            case Nil => ()
            case pathes => {
              pathes.map(p => pemKeyCertOptions.addCertPath(p))
              pgopt.setPemKeyCertOptions(pemKeyCertOptions)
            }
          }
          sslConfiguration.clientCerts match {
            case Nil => ()
            case certs => {
              certs.map(p => pemKeyCertOptions.addCertValue(Buffer.buffer(p)))
              pgopt.setPemKeyCertOptions(pemKeyCertOptions)
            }
          }
          sslConfiguration.clientCertPath.map { path =>
            pemKeyCertOptions.addCertPath(path)
            pgopt.setPemKeyCertOptions(pemKeyCertOptions)
          }
          sslConfiguration.clientCert.map { path =>
            pemKeyCertOptions.addCertValue(Buffer.buffer(path))
            pgopt.setPemKeyCertOptions(pemKeyCertOptions)
          }
          sslConfiguration.trustAll.map { v =>
            pgopt.setTrustAll(v)
          }

          pgopt
        }
    }

    maybePgConfig.getOrElse(throw new IllegalArgumentException("No suitable postgres configuration provided, you need to provide either Postgres URI or Postgres database, user and password (see https://maif.github.io/izanami/docs/guides/configuration#database for details)"))
  }
  lazy val vertx               = Vertx.vertx()

  private lazy val poolOptions = new PoolOptions()
    .setMaxSize(pgConfiguration.poolSize)
    .applyOnWithOpt(pgConfiguration.idleTimeout)((p, v) => p.setIdleTimeout(v))
    .applyOnWithOpt(pgConfiguration.maxLifetime)((p, v) => p.setMaxLifetime(v))

  //private lazy val pool = PgPool.pool(connectOptions, poolOptions)
  private lazy val pool = PgBuilder.pool().`with`(poolOptions).connectingTo(connectOptions).using(vertx).build();


  def onStart(): Future[Unit] = {
    updateSchema()
  }

  def updateSchema(): Future[Unit] = {
    val config     = new HikariConfig()
    config.setDriverClassName(classOf[org.postgresql.Driver].getName)
    config.setJdbcUrl(
      s"jdbc:postgresql://${connectOptions.getHost}:${connectOptions.getPort}/${connectOptions.getDatabase}"
    )
    config.setUsername(connectOptions.getUser)
    config.setPassword(connectOptions.getPassword)
    config.setMaximumPoolSize(2)

    val sslEnabled = sslConfiguration.enabled
    config.applyOnIf(sslEnabled) { config =>
      val mode = SslMode.of("REQUIRE")
      config.addDataSourceProperty("sslmode", mode.toString)
      config.addDataSourceProperty("ssl", true)

      if(sslConfiguration.trustAll.getOrElse(false)) {
        config.addDataSourceProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory")
      }
      config
    }
    val dataSource = new HikariDataSource(config)
    val password   = defaultPassword
    val flyway     =
      Flyway.configure
        .dataSource(dataSource)
        .locations("filesystem:conf/sql/globals", "conf/sql/globals", "sql/globals")
        .baselineOnMigrate(true)
        .schemas("izanami")
        .placeholders(
          java.util.Map.of("default_admin", defaultUser, "default_password", HashUtils.bcryptHash(password))
        )
        .load()

    val migrationResult = flyway.migrate()
    if (migrationResult.initialSchemaVersion == null) {
      val isPasswordProvided = env.typedConfiguration.admin.password.isDefined
      if (!isPasswordProvided) {
        logger.warn(
          s"No password provided in app.admin.password env variable. Therefore password ${password} has been automatically generated for RESERVED_ADMIN_USER account"
        )
      }
    }

    env.datastores.tenants
      .readTenants()
      .map(tenants => {
        tenants.foreach(tenant => {
          val flyway =
            Flyway.configure
              .dataSource(dataSource)
              .locations("filesystem:conf/sql/tenants", "filesystem:sql/tenants", "sql/tenants", "conf/sql/tenants")
              .baselineOnMigrate(true)
              .schemas(tenant.name)
              .placeholders(
                java.util.Map.of("extensions_schema", env.extensionsSchema, "schema", tenant.name)
              )
              .load()
            Try {
              val result = flyway.migrate()
            } match {
              case Failure(e:FlywayValidateException) => {
                val validationResult = flyway.validateWithResult()
                if(validationResult.invalidMigrations.asScala.map(v => v.version).contains("2")) {
                  env.logger.info(s"""Izanami needs to repair flyway migration for tenant ${tenant.name} since extension schema is now configurable. Starting repair...""")
                  flyway.repair()
                  env.logger.info(s"""Repair worked, restarting migration for ${tenant.name}""")
                  flyway.migrate()
                } else {
                  throw e
                }
              }
              case Failure(e) => throw e
              case Success(_) => ()
            }
        })
      })(env.executionContext).andThen(_ => dataSource.close())(env.executionContext)
  }

  def defaultPassword: String = {
    val maybeUserProvidedPassword = env.typedConfiguration.admin.password
    maybeUserProvidedPassword.getOrElse(IdGenerator.token(24))
  }

  def defaultUser: String = {
    env.typedConfiguration.admin.username
  }

  def onStop(): Future[Unit] = {
    pool.close().scala.map(_ => ())(env.executionContext)
  }

  def updateSearchPath(searchPath: String, conn: SqlConnection): Future[Unit] = {
    conn
      .preparedQuery(
        f"SELECT set_config('search_path', $$1, true)"
      )
      .execute(io.vertx.sqlclient.Tuple.of(searchPath))
      .mapEmpty()
      .scala
  }


  def executeInTransaction[T](callback: SqlConnection => Future[T]): Future[T] = {
    var future: io.vertx.core.Future[T] = io.vertx.core.Future.succeededFuture()
    pool
      .withTransaction(conn => {
        future = callback(conn).vertx(env.executionContext)
        future
      })
      .recover(err => {
        logger.error("Failed to execute queries in transaction", err)
        future
      })
      .scala // Bubble up query error instead of TransactionRollbackException that does not carry much information
  }

  def executeInTransaction[T](callback: SqlConnection => FutureEither[T]): FutureEither[T] = {
    executeInTransaction(conn => {
      callback(conn).value
    }).toFEither
  }

  def executeInOptionalTransaction[T](maybeTransaction: Option[SqlConnection], callback: SqlConnection => Future[T]): Future[T] = {
    maybeTransaction.fold({
      executeInTransaction(callback = callback)
    })(conn => {
      callback(conn).vertx(env.executionContext).scala
    })
  }

  def executeInOptionalTransaction[T](maybeTransaction: Option[SqlConnection], callback: SqlConnection => FutureEither[T]): FutureEither[T] = {
    executeInOptionalTransaction(maybeTransaction, conn => {
      callback(conn).value
    }).toFEither
  }

  def queryAll[A](
      query: String,
      params: List[AnyRef] = List.empty,
      debug: Boolean = false,
      conn: Option[SqlConnection] = None
  )(
      f: Row => Option[A]
  ): Future[List[A]] = {
    queryRaw[List[A]](query, params, debug, conn)(rows => rows.map(f).flatten.toList)
  }

  def queryAllOpt[A](
      query: String,
      params: List[AnyRef] = List.empty,
      debug: Boolean = false,
      conn: Option[SqlConnection] = None
  )(
      f: Row => Option[A]
  ): Future[List[Option[A]]] = {
    queryRaw[List[Option[A]]](query, params, debug, conn)(rows => rows.map(f).toList)
  }

  def queryRaw[A](
      query: String,
      params: List[AnyRef] = List.empty,
      debug: Boolean = false,
      conn: Option[SqlConnection] = None
  )(
      f: List[Row] => A
  ): Future[A] = {
    if (debug) env.logger.info(s"""query: "$query", params: "${params.map(_.toString).mkString(", ")}"""")
    val isRead = query.toLowerCase().trim.startsWith("select")
    (isRead match {
      case true  =>
        val lambda = (c: SqlConnection) => {
          c.preparedQuery(query).execute(io.vertx.sqlclient.Tuple.from(params.toArray))
        }
        conn
          .map(conn => lambda(conn))
          .map(f => f.scala)
          .getOrElse(executeInTransaction(lambda(_).scala))
      case false =>
        conn
          .map(c =>c.preparedQuery(query).execute(io.vertx.sqlclient.Tuple.from(params.toArray)).scala)
          .getOrElse(
            executeInTransaction(
              conn => conn.preparedQuery(query).execute(io.vertx.sqlclient.Tuple.from(params.toArray)).scala
            )
          )
    }).flatMap { _rows =>
      Try {
        if(Objects.isNull(_rows)) {
          throw DbConnectionFailure()
        }
        val rows = _rows.asScala.toList
        f(rows)
      } match {
        case Success(value) => FastFuture.successful(value)
        case Failure(e)     => FastFuture.failed(e)
      }
    }(env.executionContext)
      .andThen {
        case Failure(e:DbConnectionFailure) => logger.error(e.message)
        case Failure(e) => {
        val paramsToDisplay = params.map(p => {
          if(p != null && p.toString.length > 10_000) {
            p.toString.substring(0, 10_000) + "<param too long, it was truncated>"
          } else {
            p
          }
        })
        logger.error(s"""Failed to apply query: "$query" with params: "${paramsToDisplay.mkString(", ")}"""", e)
      }
      }(env.executionContext)
  }


  val pgErrorPartialFunction: PartialFunction[Throwable, IzanamiError] = {
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "featuresnamesize" => FeatureFieldTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "projectsnamesize" => ProjectFieldTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "wasm_script_configurationsnamesize" => WasmScriptNameTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "tagsnamesize" => TagFieldTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "apikeysnamesize" => ApiKeyFieldTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "global_feature_contextsnamesize" => GlobalContextNameTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "feature_contextsnamesize" => ContextNameTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "webhooksnamesize" => WebhookFieldTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "tenantnamesize" => TenantFieldTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "invitationstextsize" => EmailIsTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "usertextsize" => UsernameFieldTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "configurationtextsize" => ConfigurationFieldTooLong
    case f: PgException if f.getSqlState == CHECK_VIOLATION && f.getConstraint == "personnal_access_tokenstextsize" => PersonnalAccessTokenFieldTooLong
    case f: PgException if f.getSqlState == UNIQUE_VIOLATION && f.getConstraint == "features_pkey" => FeatureWithThisIdAlreadyExist
    case f: PgException if f.getSqlState == UNIQUE_VIOLATION && f.getConstraint == "unique_feature_name_for_project" => FeatureWithThisNameAlreadyExist
    case f: PgException if f.getSqlState == UNIQUE_VIOLATION && f.getConstraint == "new_contexts_pkey" => ContextWithThisNameAlreadyExist
    case ex => InternalServerError("An unexpected error occured")
  }

  def queryOne[A](
      query: String,
      params: List[AnyRef] = List.empty,
      debug: Boolean = false,
      conn: Option[SqlConnection] = None
  )(
      f: Row => Option[A]
  ): Future[Option[A]] = {
    queryRaw[Option[A]](query, params, debug, conn)(rows => rows.headOption.flatMap(row => f(row)))
  }

}

object PostgresqlErrors {
  val UNIQUE_VIOLATION               = "23505"
  val INTEGRITY_CONSTRAINT_VIOLATION = "23000"
  val NOT_NULL_VIOLATION             = "23502"
  val FOREIGN_KEY_VIOLATION          = "23503"
  val CHECK_VIOLATION                = "23514"
  val RELATION_DOES_NOT_EXISTS       = "42P01"
}

object pgimplicits {
  implicit class VertxFutureEnhancer[A](val future: io.vertx.core.Future[A]) extends AnyVal {
    def scala: Future[A] = {
      val promise = Promise.apply[A]()
      future.onSuccess(a => promise.trySuccess(a))
      future.onFailure { e =>
        promise.tryFailure(e)
      }
      promise.future
    }
  }

  implicit class ScalaFutureEnhancer[A](val future: Future[A]) extends AnyVal {
    def vertx(implicit ec: ExecutionContext): io.vertx.core.Future[A] = {
      val promise = io.vertx.core.Promise.promise[A]()
      future.onComplete {
        case Failure(err)   => promise.fail(err)
        case Success(value) => promise.complete(value)
      }

      promise.future
    }
  }

  implicit class VertxQueryEnhancer[A](val query: io.vertx.sqlclient.Query[A]) extends AnyVal {
    def executeAsync(): Future[A] = {
      val promise = Promise.apply[A]()
      val future  = query.execute()
      future.onSuccess(a => promise.trySuccess(a))
      future.onFailure { e =>
        promise.tryFailure(e)
      }
      promise.future
    }
  }

  implicit class VertxPreparedQueryEnhancer[A](val query: io.vertx.sqlclient.PreparedQuery[A]) extends AnyVal {
    def executeAsync(): Future[A] = {
      val promise = Promise.apply[A]()
      val future  = query.execute()
      future.onSuccess(a => promise.trySuccess(a))
      future.onFailure { e =>
        promise.tryFailure(e)
      }
      promise.future
    }
  }

  implicit class EnhancedRow(val row: Row) extends AnyVal {
    def optString(name: String): Option[String] = opt(name, "String", (a, b) => a.getString(b))

    //def optJValueArray(name: String): Option[Array[JsValue]] = opt(name, "String", (a, b) => a.getJsonObject(b))


    def optStringArray(name: String): Option[Array[String]] = opt(name, "String", (a, b) => a.getArrayOfStrings(b))

    def optUUID(name: String): Option[UUID] = opt(name, "UUID", (a, b) => a.getUUID(b))

    def opt[A](name: String, typ: String, extractor: (Row, String) => A): Option[A] = {
      Try(extractor(row, name)) match {
        case Failure(ex)    => {
          //logger.error(s"error while getting column '$name' of type $typ", ex)
          None
        }
        case Success(value) => Option(value)
      }
    }

    def optDouble(name: String): Option[Double]   = opt(name, "Double", (a, b) => a.getDouble(b).doubleValue())
    def optInt(name: String): Option[Int]         = opt(name, "Integer", (a, b) => a.getDouble(b).intValue())
    def optBoolean(name: String): Option[Boolean] = opt(name, "Boolean", (a, b) => a.getBoolean(b))
    def optLong(name: String): Option[Long]       =
      opt(name, "Long", (a, b) => a.getLong(b).longValue())

    def optDateTime(name: String): Option[OffsetDateTime] = {
      optOffsetDatetime(name).map { d =>
        val id      = if (d.getOffset.getId == "Z") "UTC" else d.getOffset.getId
        val instant = Instant.ofEpochMilli(d.toInstant.toEpochMilli)
        OffsetDateTime.ofInstant(instant, ZoneId.of(id))
      }
    }

    def optLocalDateTime(name: String): Option[LocalDateTime] = {
      opt(name, "LocalDateTime", (a, b) => a.getLocalDateTime(b))
    }

    def optOffsetDatetime(name: String): Option[OffsetDateTime] =
      opt(name, "OffsetDateTime", (a, b) => a.getOffsetDateTime(b))

    def optJsObject(name: String): Option[JsObject] =
      opt(
        name,
        "JsObject",
        (row, _) => {
          Try {
            Json.parse(row.getJsonObject(name).encode()).as[JsObject]
          } match {
            case Success(s) => s
            case Failure(e) => Json.parse(row.getString(name)).as[JsObject]
          }
        }
      )
    def optJsArray(name: String): Option[JsArray]   =
      opt(
        name,
        "JsArray",
        (row, _) => {
          Try {
            Json.parse(row.getJsonArray(name).encode()).as[JsArray]
          } match {
            case Success(s) => s
            case Failure(e) => Json.parse(row.getString(name)).as[JsArray]
          }
        }
      )
  }
}
