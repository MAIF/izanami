package fr.maif.izanami.env

import akka.http.scaladsl.util.FastFuture
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import fr.maif.izanami.datastores.HashUtils
import fr.maif.izanami.security.IdGenerator
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import io.vertx.core
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.{PemKeyCertOptions, PemTrustOptions}
import io.vertx.pgclient.impl.PgPoolImpl
import io.vertx.pgclient.pubsub.PgSubscriber
import io.vertx.pgclient.{PgConnectOptions, PgPool, SslMode}
import io.vertx.sqlclient.{PoolOptions, Row, RowSet, SqlConnection}
import org.flywaydb.core.Flyway
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.{Configuration, Logger}

import java.time.{Instant, OffsetDateTime, ZoneId}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class Postgresql(env: Env) {

  import pgimplicits._

  import scala.jdk.CollectionConverters._


  private val logger        = Logger("izanami")
  lazy val connectOptions   = if (configuration.has("app.pg.uri")) {
    logger.info(s"Postgres URI : ${configuration.get[String]("app.pg.uri")}")
    val opts = PgConnectOptions.fromUri(configuration.get[String]("app.pg.uri"))
    opts
  } else {
    val ssl        = configuration.getOptional[Configuration]("app.pg.ssl").getOrElse(Configuration.empty)
    val sslEnabled = ssl.getOptional[Boolean]("enabled").getOrElse(false)
    new PgConnectOptions()
      .applyOnWithOpt(configuration.getOptional[Int]("connect-timeout"))((p, v) => p.setConnectTimeout(v))
      .applyOnWithOpt(configuration.getOptional[Int]("idle-timeout"))((p, v) => p.setIdleTimeout(v))
      .applyOnWithOpt(configuration.getOptional[Boolean]("log-activity"))((p, v) => p.setLogActivity(v))
      .applyOnWithOpt(configuration.getOptional[Int]("pipelining-limit"))((p, v) => p.setPipeliningLimit(v))
      .setPort(getPort)
      .setHost(getHost)
      .setDatabase(configuration.getOptional[String]("app.pg.database").getOrElse("postgres"))
      .setUser(configuration.getOptional[String]("app.pg.user").getOrElse("postgres"))
      .setPassword(configuration.getOptional[String]("app.pg.password").getOrElse("postgres"))
      .applyOnIf(sslEnabled) { pgopt =>
        val mode              = SslMode.of(ssl.getOptional[String]("mode").getOrElse("VERIFY_CA"))
        val pemTrustOptions   = new PemTrustOptions()
        val pemKeyCertOptions = new PemKeyCertOptions()
        pgopt.setSslMode(mode)
        pgopt.applyOnWithOpt(ssl.getOptional[Int]("ssl-handshake-timeout"))((p, v) => p.setSslHandshakeTimeout(v))
        ssl.getOptional[Seq[String]]("trustedCertsPath").map { pathes =>
          pathes.map(p => pemTrustOptions.addCertPath(p))
          pgopt.setPemTrustOptions(pemTrustOptions)
        }
        ssl.getOptional[String]("trusted-cert-path").map { path =>
          pemTrustOptions.addCertPath(path)
          pgopt.setPemTrustOptions(pemTrustOptions)
        }
        ssl.getOptional[Seq[String]]("trusted-certs").map { certs =>
          certs.map(p => pemTrustOptions.addCertValue(Buffer.buffer(p)))
          pgopt.setPemTrustOptions(pemTrustOptions)
        }
        ssl.getOptional[String]("trusted-cert").map { path =>
          pemTrustOptions.addCertValue(Buffer.buffer(path))
          pgopt.setPemTrustOptions(pemTrustOptions)
        }
        ssl.getOptional[Seq[String]]("client-certs-path").map { pathes =>
          pathes.map(p => pemKeyCertOptions.addCertPath(p))
          pgopt.setPemKeyCertOptions(pemKeyCertOptions)
        }
        ssl.getOptional[Seq[String]]("client-certs").map { certs =>
          certs.map(p => pemKeyCertOptions.addCertValue(Buffer.buffer(p)))
          pgopt.setPemKeyCertOptions(pemKeyCertOptions)
        }
        ssl.getOptional[String]("client-cert-path").map { path =>
          pemKeyCertOptions.addCertPath(path)
          pgopt.setPemKeyCertOptions(pemKeyCertOptions)
        }
        ssl.getOptional[String]("client-cert").map { path =>
          pemKeyCertOptions.addCertValue(Buffer.buffer(path))
          pgopt.setPemKeyCertOptions(pemKeyCertOptions)
        }
        ssl.getOptional[Boolean]("trust-all").map { v =>
          pgopt.setTrustAll(v)
        }
        pgopt
      }
  }
  lazy val vertx = Vertx.vertx()
  private lazy val poolOptions = new PoolOptions()
    .setMaxSize(configuration.getOptional[Int]("app.pg.pool-size").getOrElse(100))
  private lazy val pool = PgPool.pool(connectOptions, poolOptions)

  private val configuration = env.configuration

  def getHost = {
    configuration.getOptional[String]("app.pg.host").getOrElse("localhost")
  }

  def getPort = {
    configuration.getOptional[Int]("app.pg.port").getOrElse(5432)
  }

  def onStart(): Future[Unit] = {
    updateSchema()
    Future.successful(())
  }

  def updateSchema(): Unit = {
    val config     = new HikariConfig()
    config.setDriverClassName(classOf[org.postgresql.Driver].getName)
    config.setJdbcUrl(
      s"jdbc:postgresql://${connectOptions.getHost}:${connectOptions.getPort}/${connectOptions.getDatabase}"
    )
    config.setUsername(connectOptions.getUser)
    config.setPassword(connectOptions.getPassword)
    config.setMaximumPoolSize(10)
    val dataSource = new HikariDataSource(config)
    val password = defaultPassword
    val flyway     =
      Flyway.configure
        .dataSource(dataSource)
        .locations("filesystem:conf/sql/globals", "conf/sql/globals", "sql/globals")
        .baselineOnMigrate(true)
        .schemas("izanami")
        .placeholders(java.util.Map.of("default_admin", "RESERVED_ADMIN_USER", "default_password", HashUtils.bcryptHash(password)))
        .load()
    val migrationResult = flyway.migrate()
    if(migrationResult.initialSchemaVersion == null) {
      val isPasswordProvided = configuration.getOptional[String]("app.admin.password").isDefined
      if(!isPasswordProvided) {
        logger.warn(
          s"No password provided in app.admin.password env variable. Therefore password ${password} has been automatically generated for RESERVED_ADMIN_USER account"
        )
      }
    }
    dataSource.close()
  }

  def defaultPassword: String = {
    val maybeUserProvidedPassword = configuration.getOptional[String]("app.admin.password")
    maybeUserProvidedPassword.getOrElse(IdGenerator.token(24))
  }

  def onStop(): Future[Unit] = {
    pool.close()
    FastFuture.successful(())
  }


  def updateSearchPath(searchPath: String, conn: SqlConnection): Future[Unit] = {
    conn.preparedQuery(
      f"SELECT set_config('search_path', $$1, true)"
    )
    .execute(io.vertx.sqlclient.Tuple.of(searchPath)).mapEmpty().scala
  }

  private def setSearchPath(schemas: Set[String], conn: SqlConnection): io.vertx.core.Future[RowSet[Row]] = {
    if (schemas.nonEmpty) {
      conn.preparedQuery(f"SELECT set_config('search_path', $$1, true)").execute(io.vertx.sqlclient.Tuple.of(schemas.mkString(",")))
    } else {
      io.vertx.core.Future.succeededFuture()
    }
  }

  def executeInTransaction[T](callback: SqlConnection => Future[T], schemas: Set[String] = Set()): Future[T] = {
    var future: io.vertx.core.Future[T] = io.vertx.core.Future.succeededFuture()
    pool
      .withTransaction(conn => {
        var searchPathFuture = setSearchPath(schemas, conn)
        future = searchPathFuture.flatMap(_ => callback(conn).vertx(env.executionContext))
        future
      })
      .recover(err => {
        logger.error("Failed to execute queries in transaction", err)
        future
      })
      .scala // Bubble up query error instead of TransactionRollbackException that does not carry much information
  }

  def queryAll[A](
      query: String,
      params: List[AnyRef] = List.empty,
      debug: Boolean = false,
      schemas: Set[String] = Set(),
      conn: Option[SqlConnection] = None
  )(
      f: Row => Option[A]
  ): Future[List[A]] = {
    queryRaw[List[A]](query, params, debug, schemas, conn)(rows => rows.map(f).flatten.toList)
  }

  def queryAllOpt[A](
      query: String,
      params: List[AnyRef] = List.empty,
      debug: Boolean = false,
      schemas: Set[String] = Set(),
      conn: Option[SqlConnection] = None
  )(
      f: Row => Option[A]
  ): Future[List[Option[A]]] = {
    queryRaw[List[Option[A]]](query, params, debug, schemas, conn)(rows => rows.map(f).toList)
  }

  def queryRaw[A](
      query: String,
      params: List[AnyRef] = List.empty,
      debug: Boolean = false,
      schemas: Set[String] = Set(),
      conn: Option[SqlConnection] = None
  )(
      f: List[Row] => A
  ): Future[A] = {
    if (debug) env.logger.info(s"""query: "$query", params: "${params.mkString(", ")}"""")
    val isRead = query.toLowerCase().trim.startsWith("select")
    (isRead match {
      case true  =>
        val lambda = (c: SqlConnection) => {
          c.preparedQuery(query).execute(io.vertx.sqlclient.Tuple.from(params.toArray))
        }
        conn
          .map(conn => setSearchPath(schemas, conn).flatMap(_ => lambda(conn)))
          .map(f => f.scala)
          .getOrElse(executeInTransaction(lambda(_).scala, schemas))
      case false =>
        conn
          .map(c => setSearchPath(schemas, c).flatMap(_ => c.preparedQuery(query).execute(io.vertx.sqlclient.Tuple.from(params.toArray))).scala)
          .getOrElse(executeInTransaction(conn => conn.preparedQuery(query).execute(io.vertx.sqlclient.Tuple.from(params.toArray)).scala, schemas))
    }).flatMap { _rows =>
      Try {
        val rows = _rows.asScala.toList
        f(rows)
      } match {
        case Success(value) => FastFuture.successful(value)
        case Failure(e)     => FastFuture.failed(e)
      }
    }(env.executionContext)
      .andThen { case Failure(e) =>
        logger.error(s"""Failed to apply query: "$query" with params: "${params.mkString(", ")}"""", e)
      }(env.executionContext)
  }

  def queryOne[A](
      query: String,
      params: List[AnyRef] = List.empty,
      debug: Boolean = false,
      schemas: Set[String] = Set(),
      conn: Option[SqlConnection] = None
  )(
      f: Row => Option[A]
  ): Future[Option[A]] = {
    queryRaw[Option[A]](query, params, debug, schemas, conn)(rows => rows.headOption.flatMap(row => f(row)))
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
    def optString(name: String): Option[String]                 = opt(name, "String", (a, b) => a.getString(b))

    def optStringArray(name: String): Option[Array[String]] = opt(name, "String", (a, b) => a.getArrayOfStrings(b))

    def optUUID(name: String): Option[UUID]  = opt(name, "UUID", (a, b) => a.getUUID(b))

    def opt[A](name: String, typ: String, extractor: (Row, String) => A): Option[A] = {
      Try(extractor(row, name)) match {
        case Failure(ex)    => {
          //logger.error(s"error while getting column '$name' of type $typ", ex)
          None
        }
        case Success(value) => Option(value)
      }
    }

    def optDouble(name: String): Option[Double]                 = opt(name, "Double", (a, b) => a.getDouble(b).doubleValue())
    def optInt(name: String): Option[Int]                       = opt(name, "Integer", (a, b) => a.getDouble(b).intValue())
    def optBoolean(name: String): Option[Boolean]               = opt(name, "Boolean", (a, b) => a.getBoolean(b))
    def optLong(name: String): Option[Long]                     =
      opt(name, "Long", (a, b) => a.getLong(b).longValue())

    def optDateTime(name: String): Option[OffsetDateTime] = {
      optOffsetDatetime(name).map { d =>
        val id = if (d.getOffset.getId == "Z") "UTC" else d.getOffset.getId
        val instant = Instant.ofEpochMilli(d.toInstant.toEpochMilli)
        OffsetDateTime.ofInstant(instant, ZoneId.of(id))
      }
    }

    def optOffsetDatetime(name: String): Option[OffsetDateTime] =
      opt(name, "OffsetDateTime", (a, b) => a.getOffsetDateTime(b))

    def optJsObject(name: String): Option[JsObject]             =
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
    def optJsArray(name: String): Option[JsArray]               =
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
