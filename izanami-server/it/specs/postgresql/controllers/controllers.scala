package specs.postgresql.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.postgresql._
import controllers._
import scala.util.Random


class PostgresqlApikeyControllerSpec extends ApikeyControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlConfigControllerSpec extends ConfigControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlExperimentControllerSpec extends ExperimentControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlFeatureControllerSpec extends FeatureControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlUserControllerSpec extends UserControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlWebhookControllerSpec extends WebhookControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))


