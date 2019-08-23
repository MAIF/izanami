package specs.cassandra.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.cassandra._
import controllers._

class CassandraApikeyControllerSpec extends ApikeyControllerSpec("Cassandra", Configs.cassandraConfiguration(s"apikey${Configs.idGenerator.nextId()}"))
class CassandraConfigControllerSpec extends ConfigControllerSpec("Cassandra", Configs.cassandraConfiguration(s"config${Configs.idGenerator.nextId()}"))
class CassandraExperimentControllerSpec extends ExperimentControllerSpec("Cassandra", Configs.cassandraConfiguration(s"experiment${Configs.idGenerator.nextId()}"))
class CassandraFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Cassandra", Configs.cassandraConfiguration(s"featureaccess${Configs.idGenerator.nextId()}"))
class CassandraFeatureControllerSpec extends FeatureControllerSpec("Cassandra", Configs.cassandraConfiguration(s"feature${Configs.idGenerator.nextId()}"))
class CassandraGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Cassandra", Configs.cassandraConfiguration(s"globalscript${Configs.idGenerator.nextId()}"))
class CassandraUserControllerSpec extends UserControllerSpec("Cassandra", Configs.cassandraConfiguration(s"user${Configs.idGenerator.nextId()}"))
class CassandraWebhookControllerSpec extends WebhookControllerSpec("Cassandra", Configs.cassandraConfiguration(s"webhook${Configs.idGenerator.nextId()}"))

