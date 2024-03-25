package fr.maif.izanami.api

import com.typesafe.config.ConfigFactory
import fr.maif.izanami.IzanamiLoader
import fr.maif.izanami.api.Tests.{isAvailable, startServer}
import org.scalatest._
import org.slf4j.LoggerFactory
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import play.api.ApplicationLoader.Context
import play.api.inject.DefaultApplicationLifecycle
import play.api.test.{DefaultTestServerFactory, RunningServer}
import play.api.{Application, Configuration, Environment, Mode}
import play.core.server.ServerConfig

import java.io.{File, IOException}
import java.net.Socket

class IzanamiServerFactory extends DefaultTestServerFactory {
  override def serverConfig(app: Application): ServerConfig = {
    val sc = ServerConfig(port = Some(9000), sslPort = None, mode = Mode.Test, rootDir = app.path)
    sc.copy(configuration = sc.configuration ++ overrideServerConfiguration(app))
  }
}

trait IzanamiServerTest extends TestSuiteMixin { this: TestSuite =>
  var maybeContainers: Option[DockerComposeContainer[Nothing]] = None
  abstract override def run(testName: Option[String], args: Args): Status = {
    lazy val status = super.run(testName, args)
    if(isAvailable(5432)) {
      println("Port 5432 is available, starting docker-compose for the current suite")
      var containers = new DockerComposeContainer(new File("docker-compose.yml"))
      containers = containers.withLocalCompose(true).asInstanceOf[DockerComposeContainer[Nothing]]

      containers.start()
      maybeContainers = Some(containers)
    } else {
      println("Port 5432 is taken, assuming that docker containers are already running")
    }
    if(isAvailable(9000)) {
      println("Port 9000 is available, starting server for the current suite")
      val runningServer = startServer()
      try {
        status.whenCompleted { _ =>
          runningServer.stopServer.close()
          maybeContainers.foreach(_.close())
        }
      } catch { // In case the suite aborts, ensure the server is stopped
        case ex: Throwable =>
          runningServer.stopServer.close()
          maybeContainers.foreach(_.close())
          throw ex
      }
    } else {
      println("Port 9000 is taken, assuming that Izanami is running")
    }

    status
  }
}

object Tests {
  def isAvailable(port: Int): Boolean = {
    var socket: Option[Socket] = None
    try {
      socket = Some(new Socket("localhost", port))
      false
    } catch {
      case _: IOException => true
    } finally {
      socket.foreach(_.close())
    }
  }

  def startServer(): RunningServer = {
    lazy val config = ConfigFactory.parseFile(new File("conf/dev.conf")).resolve()

    lazy val configuration: Configuration =
      Configuration.load(Environment.simple(), Map.empty[String, AnyRef]).withFallback(Configuration(config))

    lazy val application = new IzanamiLoader().load(
      Context(
        environment = Environment.simple(),
        devContext = None,
        lifecycle = new DefaultApplicationLifecycle(),
        initialConfiguration = configuration
      )
    )

    lazy val server = new IzanamiServerFactory()

    server.start(application)
  }
}
/*
class Tests
    extends Suites(
      new ApplicationKeysAPISpec(),
      new ConfigurationAPISpec(),
      new FeatureAPISpec(),
      new FeatureClientAPISpec(),
      new FeatureContextAPISpec(),
      new ImportApiSpec(),
      new LoginAPISpec(),
      new PluginAPISpec(),
      new ProjectAPISpec(),
      new TagAPISpec(),
      new TenantAPISpec(),
      new UsersAPISpec(),
      new V1CompatibilityTest()
    )
    with BeforeAndAfterAll {

  var maybeServer: Option[RunningServer] = None
  var maybeContainers: Option[DockerComposeContainer[Nothing]] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (isAvailable(5432)) {
      println("Port 5432 is available, starting docker-compose once for all suites")
      val containers = new DockerComposeContainer(new File("docker-compose.yml"))
      containers.start()
    } else {
      println("Port 5432 is busy, assuming that docker-compose is already started")
    }
    if(isAvailable(9000)) {
      println("Port 9000 is free, starting one izanami instances once for all suites")
      maybeServer = Some(startServer())
    } else {
      println("Port 9000 is taken, assuming that Izanami is already running")
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    maybeServer.foreach(_.stopServer.close())
    maybeContainers.foreach(_.close())
  }

}*/