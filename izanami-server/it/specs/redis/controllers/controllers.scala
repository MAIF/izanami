package specs.redis.controllers

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.redis._
import controllers._
import scala.util.Random


class RedisApikeyControllerSpec extends ApikeyControllerSpec("Redis", Configs.redisConfiguration)
class RedisConfigControllerSpec extends ConfigControllerSpec("Redis", Configs.redisConfiguration)
class RedisExperimentControllerSpec extends ExperimentControllerSpec("Redis", Configs.redisConfiguration)
class RedisFeatureControllerWildcardAccessSpec extends FeatureControllerWildcardAccessSpec("Redis", Configs.redisConfiguration)
class RedisFeatureControllerSpec extends FeatureControllerSpec("Redis", Configs.redisConfiguration)
class RedisGlobalScriptControllerSpec extends GlobalScriptControllerSpec("Redis", Configs.redisConfiguration)
class RedisUserControllerSpec extends UserControllerSpec("Redis", Configs.redisConfiguration)
class RedisWebhookControllerSpec extends WebhookControllerSpec("Redis", Configs.redisConfiguration)



