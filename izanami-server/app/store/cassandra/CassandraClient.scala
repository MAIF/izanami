package store.cassandra

import java.net.InetSocketAddress

import com.datastax.driver.core.{Cluster, Session}
import env.CassandraConfig
import libs.logs.{IzanamiLogger, ZLogger}
import zio.{Task, UIO, ZManaged}

object CassandraClient {

  def cassandraClient(mayBeConfig: Option[CassandraConfig]): ZManaged[ZLogger, Throwable, Option[(Cluster, Session)]] =
    mayBeConfig
      .map { config =>
        ZManaged
          .make(
            ZLogger.info(s"Initializing Cassandra cluster for ${config}") *> Task {
              val adds = config.addresses.map { add =>
                val Array(host, port) = add.split(":")
                new InetSocketAddress(host, port.toInt)
              }
              val builder: Cluster.Builder = Cluster.builder
                .withoutJMXReporting()
                .addContactPointsWithPorts(adds: _*)

              val b: Cluster.Builder = config.clusterName.map(builder.withClusterName).getOrElse(builder)

              val cluster: Cluster = (for {
                username <- config.username
                password <- config.password
              } yield {
                b.withCredentials(username, password)
              }).getOrElse(b).build()

              cluster.init()

              val session = cluster.connect()

              cluster.connect().execute(s"""
                                     |CREATE KEYSPACE IF NOT EXISTS ${config.keyspace} WITH REPLICATION = {
                                     | 'class' : 'SimpleStrategy', 'replication_factor' : ${config.replicationFactor}
                                     |}""".stripMargin)

              (cluster, session)
            }
          )(t => UIO(t._1.close))
          .map(Some.apply)

      }
      .getOrElse(ZManaged.effectTotal(None))
}
