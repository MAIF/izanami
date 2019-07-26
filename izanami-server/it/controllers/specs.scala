package controllers

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables._
import test.{IzanamiMatchers, OneServerPerSuiteWithMyComponents}
import scala.util.Random
import org.scalatest.BeforeAndAfterAll


class InMemoryApikeyControllerSpec extends ApikeyControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryConfigControllerSpec extends ConfigControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryExperimentControllerSpec extends ExperimentControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryFeatureControllerSpec extends FeatureControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryGlobalScriptControllerSpec extends GlobalScriptControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryUserControllerSpec extends UserControllerSpec("InMemory", Configs.inMemoryConfiguration)
class InMemoryWebhookControllerSpec extends WebhookControllerSpec("InMemory", Configs.inMemoryConfiguration)


class InMemoryWithDbApikeyControllerSpec extends ApikeyControllerSpec("InMemoryWithDb", Configs.inMemoryWithDbConfiguration)
class InMemoryWithDbConfigControllerSpec extends ConfigControllerSpec("InMemoryWithDb", Configs.inMemoryWithDbConfiguration)
class InMemoryWithDbExperimentControllerSpec extends ExperimentControllerSpec("InMemoryWithDb", Configs.inMemoryWithDbConfiguration)
class InMemoryWithDbFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("InMemoryWithDb", Configs.inMemoryWithDbConfiguration)
class InMemoryWithDbFeatureControllerSpec extends FeatureControllerSpec("InMemoryWithDb", Configs.inMemoryWithDbConfiguration)
class InMemoryWithDbGlobalScriptControllerSpec extends GlobalScriptControllerSpec("InMemoryWithDb", Configs.inMemoryWithDbConfiguration)
class InMemoryWithDbUserControllerSpec extends UserControllerSpec("InMemoryWithDb", Configs.inMemoryWithDbConfiguration)
class InMemoryWithDbWebhookControllerSpec extends WebhookControllerSpec("InMemoryWithDb", Configs.inMemoryWithDbConfiguration)


class RedisApikeyControllerSpec extends ApikeyControllerSpec("Redis", Configs.redisConfiguration)
class RedisConfigControllerSpec extends ConfigControllerSpec("Redis", Configs.redisConfiguration)
class RedisExperimentControllerSpec extends ExperimentControllerSpec("Redis", Configs.redisConfiguration)
class RedisFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Redis", Configs.redisConfiguration)
class RedisFeatureControllerSpec extends FeatureControllerSpec("Redis", Configs.redisConfiguration)
class RedisGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Redis", Configs.redisConfiguration)
class RedisUserControllerSpec extends UserControllerSpec("Redis", Configs.redisConfiguration)
class RedisWebhookControllerSpec extends WebhookControllerSpec("Redis", Configs.redisConfiguration)


// class ElasticApikeyControllerSpec extends ApikeyControllerSpec("Elastic", Configs.elasticConfiguration) with BeforeAndAfterAll {
//   override protected def beforeAll(): Unit = Configs.initEs
// }
// class ElasticConfigControllerSpec extends ConfigControllerSpec("Elastic", Configs.elasticConfiguration) with BeforeAndAfterAll {
//   override protected def beforeAll(): Unit = Configs.initEs
// }
// class ElasticExperimentControllerSpec extends ExperimentControllerSpec("Elastic", Configs.elasticConfiguration) with BeforeAndAfterAll {
//   override protected def beforeAll(): Unit = Configs.initEs
// }
// class ElasticFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Elastic", Configs.elasticConfiguration) with BeforeAndAfterAll {
//   override protected def beforeAll(): Unit = Configs.initEs
// }
// class ElasticFeatureControllerSpec extends FeatureControllerSpec("Elastic", Configs.elasticConfiguration) with BeforeAndAfterAll {
//   override protected def beforeAll(): Unit = Configs.initEs
// }
// class ElasticGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Elastic", Configs.elasticConfiguration) with BeforeAndAfterAll {
//   override protected def beforeAll(): Unit = Configs.initEs
// }
// class ElasticUserControllerSpec extends UserControllerSpec("Elastic", Configs.elasticConfiguration) with BeforeAndAfterAll {
//   override protected def beforeAll(): Unit = Configs.initEs
// }
// class ElasticWebhookControllerSpec extends WebhookControllerSpec("Elastic", Configs.elasticConfiguration) with BeforeAndAfterAll {
//   override protected def beforeAll(): Unit = Configs.initEs
// }


//class CassandraApikeyControllerSpec extends ApikeyControllerSpec("Cassandra", Configs.cassandraConfiguration(s"apikey${Configs.idGenerator.nextId()}"))
//class CassandraConfigControllerSpec extends ConfigControllerSpec("Cassandra", Configs.cassandraConfiguration(s"config${Configs.idGenerator.nextId()}"))
//class CassandraExperimentControllerSpec extends ExperimentControllerSpec("Cassandra", Configs.cassandraConfiguration(s"experiment${Configs.idGenerator.nextId()}"))
//class CassandraFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Cassandra", Configs.cassandraConfiguration(s"featureaccess${Configs.idGenerator.nextId()}"))
//class CassandraFeatureControllerSpec extends FeatureControllerSpec("Cassandra", Configs.cassandraConfiguration(s"feature${Configs.idGenerator.nextId()}"))
//class CassandraGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Cassandra", Configs.cassandraConfiguration(s"globalscript${Configs.idGenerator.nextId()}"))
//class CassandraUserControllerSpec extends UserControllerSpec("Cassandra", Configs.cassandraConfiguration(s"user${Configs.idGenerator.nextId()}"))
//class CassandraWebhookControllerSpec extends WebhookControllerSpec("Cassandra", Configs.cassandraConfiguration(s"webhook${Configs.idGenerator.nextId()}"))


class LevelDbApikeyControllerSpec extends ApikeyControllerSpec("LevelDb", Configs.levelDBConfiguration(s"apikey-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
  override protected def afterAll(): Unit = Configs.cleanLevelDb
}
class LevelDbConfigControllerSpec extends ConfigControllerSpec("LevelDb", Configs.levelDBConfiguration(s"config-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
  override protected def afterAll(): Unit = Configs.cleanLevelDb
}
class LevelDbExperimentControllerSpec extends ExperimentControllerSpec("LevelDb", Configs.levelDBConfiguration(s"experiment-${Random.nextInt(1000)}"), parallelism = 1) with BeforeAndAfterAll {
  override protected def afterAll(): Unit = Configs.cleanLevelDb
}
class LevelDbFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("LevelDb", Configs.levelDBConfiguration(s"featureaccess-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
  override protected def afterAll(): Unit = Configs.cleanLevelDb
}
class LevelDbFeatureControllerSpec extends FeatureControllerSpec("LevelDb", Configs.levelDBConfiguration(s"feature-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
  override protected def afterAll(): Unit = Configs.cleanLevelDb
}
class LevelDbGlobalScriptControllerSpec extends GlobalScriptControllerSpec("LevelDb", Configs.levelDBConfiguration(s"globalscript-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
  override protected def afterAll(): Unit = Configs.cleanLevelDb
}
class LevelDbUserControllerSpec extends UserControllerSpec("LevelDb", Configs.levelDBConfiguration(s"user-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
  override protected def afterAll(): Unit = Configs.cleanLevelDb
}
class LevelDbWebhookControllerSpec extends WebhookControllerSpec("LevelDb", Configs.levelDBConfiguration(s"webhook-${Random.nextInt(1000)}")) with BeforeAndAfterAll {
  override protected def afterAll(): Unit = Configs.cleanLevelDb
}


class MongoApikeyControllerSpec extends ApikeyControllerSpec("Mongo", Configs.mongoConfig("apikey"))
class MongoConfigControllerSpec extends ConfigControllerSpec("Mongo", Configs.mongoConfig("config"))
class MongoExperimentControllerSpec extends ExperimentControllerSpec("Mongo", Configs.mongoConfig("experiment"))
class MongoFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Mongo", Configs.mongoConfig("featureaccess"))
class MongoFeatureControllerSpec extends FeatureControllerSpec("Mongo", Configs.mongoConfig("feature"))
class MongoGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Mongo", Configs.mongoConfig("globalscript"))
class MongoUserControllerSpec extends UserControllerSpec("Mongo", Configs.mongoConfig("user"))
class MongoWebhookControllerSpec extends WebhookControllerSpec("Mongo", Configs.mongoConfig("webhook"))


//class DynamoApikeyControllerSpec extends ApikeyControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
//class DynamoExperimentControllerSpec extends ExperimentControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
//class DynamoConfigControllerSpec extends ConfigControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
//class DynamoFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
//class DynamoFeatureControllerSpec extends FeatureControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
//class DynamoGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
//class DynamoUserControllerSpec extends UserControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))
//class DynamoWebhookControllerSpec extends WebhookControllerSpec("Dynamo", Configs.dynamoDbConfig(Random.nextInt(1000)))


class PostgresqlApikeyControllerSpec extends ApikeyControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlConfigControllerSpec extends ConfigControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlExperimentControllerSpec extends ExperimentControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlFeatureControllerSpec extends FeatureControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlUserControllerSpec extends UserControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))
class PostgresqlWebhookControllerSpec extends WebhookControllerSpec("Postgresql", Configs.pgConfig(Random.nextInt(1000)))





