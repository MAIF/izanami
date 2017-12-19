package store.cassandra

import java.net.InetSocketAddress

import com.datastax.driver.core.Cluster
import env.CassandraConfig
import play.api.Logger

object CassandraClient {

  def cassandraClient(mayBeConfig: Option[CassandraConfig]): Option[Cluster] =
    mayBeConfig.map { config =>
      Logger.info(s"Initializing Cassandra cluster for ${config}")

      val adds = config.addresses.map { add =>
        val Array(host, port) = add.split(":")
        new InetSocketAddress(host, port.toInt)
      }
      val builder = Cluster
        .builder()
        .addContactPointsWithPorts(adds: _*)

      val b = config.clusterName.map(builder.withClusterName).getOrElse(builder)

      val cluster = b.build()

      cluster.connect().execute(s"""
                                   |CREATE KEYSPACE IF NOT EXISTS ${config.keyspace} WITH REPLICATION = {
                                   | 'class' : 'SimpleStrategy', 'replication_factor' : ${config.replicationFactor}
                                   |}
        """.stripMargin)

      cluster
    }
}
