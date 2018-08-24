package store.leveldb

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.regex.Pattern

import akka.actor.ActorSystem
import akka.util.ByteString
import cats.effect.Async
import org.iq80.leveldb._

import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

trait RedisLike[F[_]] {
  def start(): Unit = {}
  def stop(): Unit
  def flushall(): F[Boolean]
  def get(key: String): F[Option[ByteString]]
  def mget(keys: String*): F[Seq[Option[ByteString]]]
  def set(key: String, value: String, exSeconds: Option[Long] = None, pxMilliseconds: Option[Long] = None): F[Boolean]
  def setBS(key: String,
            value: ByteString,
            exSeconds: Option[Long] = None,
            pxMilliseconds: Option[Long] = None): F[Boolean]
  def del(keys: String*): F[Long]
  def incr(key: String): F[Long]
  def incrby(key: String, increment: Long): F[Long]
  def exists(key: String): F[Boolean]
  def keys(pattern: String): F[Seq[String]]
  def hdel(key: String, fields: String*): F[Long]
  def hgetall(key: String): F[Map[String, ByteString]]
  def hset(key: String, field: String, value: String): F[Boolean]
  def hsetBS(key: String, field: String, value: ByteString): F[Boolean]
  def llen(key: String): F[Long]
  def lpush(key: String, values: String*): F[Long]
  def lpushLong(key: String, values: Long*): F[Long]
  def lpushBS(key: String, values: ByteString*): F[Long]
  def lrange(key: String, start: Long, stop: Long): F[Seq[ByteString]]
  def ltrim(key: String, start: Long, stop: Long): F[Boolean]
  def pttl(key: String): F[Long]
  def ttl(key: String): F[Long]
  def expire(key: String, seconds: Int): F[Boolean]
  def pexpire(key: String, milliseconds: Long): F[Boolean]
  def sadd(key: String, members: String*): F[Long]
  def saddBS(key: String, members: ByteString*): F[Long]
  def sismember(key: String, member: String): F[Boolean]
  def sismemberBS(key: String, member: ByteString): F[Boolean]
  def smembers(key: String): F[Seq[ByteString]]
  def srem(key: String, members: String*): F[Long]
  def sremBS(key: String, members: ByteString*): F[Long]
//  def zrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]];
//  def zadd(key: String, scoreMembers: (Double, String)*): Future[Long]
}

class LevelDBRedisCommand[F[_]: Async](db: DB, actorSystem: ActorSystem) extends RedisLike[F] {

  import cats.implicits._
  import actorSystem.dispatcher
  import org.iq80.leveldb._
  import org.iq80.leveldb.impl.Iq80DBFactory._

  import collection.JavaConverters._
  import scala.concurrent.duration._

  private val options     = new Options().createIfMissing(true)
  private val expirations = new ConcurrentHashMap[String, Long]()

  private val cancel = actorSystem.scheduler.schedule(0.millis, 10.millis) {
    val time = System.currentTimeMillis()
    expirations.entrySet().asScala.foreach { entry =>
      if (entry.getValue < time) {
        db.delete(bytes(entry.getKey))
        expirations.remove(entry.getKey)
      }
    }
    ()
  }

  private def lift[T](body: => T): F[T] =
    Async[F].async { cb =>
      try {
        cb(Right(body))
      } catch {
        case NonFatal(e) => cb(Left(e))
      }
    }

  private def getAllKeys(): Seq[String] = {
    var keys     = Seq.empty[String]
    val iterator = db.iterator()
    iterator.seekToFirst()
    while (iterator.hasNext) {
      val key = asString(iterator.peekNext.getKey)
      keys = keys :+ key
      iterator.next
    }
    keys
  }

  private def getValueAt(key: String): Option[String] =
    Try(db.get(bytes(key))).toOption.flatMap(s => Option(asString(s)))

  override def stop(): Unit = {
    cancel.cancel()
    db.close()
  }

  override def flushall(): F[Boolean] =
    lift {
      getAllKeys().foreach(k => db.delete(bytes(k)))
      true
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def get(key: String): F[Option[ByteString]] =
    lift {
      getValueAt(key).map(ByteString.apply)
    }

  override def set(key: String,
                   value: String,
                   exSeconds: Option[Long] = None,
                   pxMilliseconds: Option[Long] = None): F[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String,
                     value: ByteString,
                     exSeconds: Option[Long] = None,
                     pxMilliseconds: Option[Long] = None): F[Boolean] =
    lift {
      db.put(bytes(key), value.toArray[Byte])
      if (exSeconds.isDefined) {
        expire(key, exSeconds.get.toInt)
      }
      if (pxMilliseconds.isDefined) {
        pexpire(key, pxMilliseconds.get)
      }
      true
    }

  override def del(keys: String*): F[Long] =
    lift {
      keys
        .map { k =>
          db.delete(bytes(k))
          1L
        }
        .foldLeft(0L)((a, b) => a + b)
    }

  override def incr(key: String): F[Long] = incrby(key, 1L)

  override def incrby(key: String, increment: Long): F[Long] =
    lift {
      val value    = getValueAt(key).map(_.toLong).getOrElse(0L)
      val newValue = value + increment
      db.put(bytes(key), bytes(newValue.toString))
      newValue
    }

  override def exists(key: String): F[Boolean] =
    lift(getValueAt(key).isDefined)

  override def mget(keys: String*): F[Seq[Option[ByteString]]] =
    keys.toList.traverse(k => get(k)).map(_.toSeq)

  override def keys(pattern: String): F[Seq[String]] =
    lift {
      getAllKeys().filter { k =>
        val regex = pattern.replaceAll("\\*", ".*")
        val pat   = Pattern.compile(regex)
        pat.matcher(k).find
      }
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def getMapAt(key: String): Map[String, ByteString] =
    getValueAt(key)
      .map(_.split(";;;").map { v =>
        val parts = v.split("<#>")
        (parts.head, ByteString(parts.last))
      }.toMap)
      .getOrElse(Map.empty[String, ByteString])

  private def setMapAt(key: String, set: Map[String, ByteString]): Unit =
    db.put(bytes(key), bytes(set.map(t => s"${t._1}<#>${t._2.utf8String}").mkString(";;;")))

  override def hdel(key: String, fields: String*): F[Long] =
    lift {
      val hash    = getMapAt(key)
      val newHash = hash.filterNot(t => fields.contains(t._1))
      setMapAt(key, newHash)
      fields.size
    }

  override def hgetall(key: String): F[Map[String, ByteString]] =
    lift {
      val hash = getMapAt(key)
      hash
    }

  override def hset(key: String, field: String, value: String): F[Boolean] =
    hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): F[Boolean] =
    lift {
      val hash    = getMapAt(key)
      val newHash = hash + ((field, value))
      setMapAt(key, newHash)
      true
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def getListAt(key: String): Seq[ByteString] =
    getValueAt(key)
      .map(_.split(";;;").toSeq.map(ByteString.apply))
      .getOrElse(Seq.empty[ByteString])

  private def setListAt(key: String, set: Seq[ByteString]): Unit =
    db.put(bytes(key), bytes(set.map(_.utf8String).mkString(";;;")))

  override def llen(key: String): F[Long] =
    lift {
      getListAt(key).size
    }

  override def lpush(key: String, values: String*): F[Long] =
    lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): F[Long] =
    lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): F[Long] =
    lift {
      val seq    = getListAt(key)
      val newSeq = values ++ seq
      setListAt(key, newSeq)
      values.size
    }

  override def lrange(key: String, start: Long, stop: Long): F[Seq[ByteString]] =
    lift {
      val seq    = getListAt(key)
      val result = seq.slice(start.toInt, stop.toInt - start.toInt)
      result
    }

  override def ltrim(key: String, start: Long, stop: Long): F[Boolean] =
    lift {
      val seq    = getListAt(key)
      val result = seq.slice(start.toInt, stop.toInt - start.toInt)
      setListAt(key, result)
      true
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): F[Long] =
    lift {
      Option(expirations.get(key))
        .map(e => {
          val ttlValue = e - System.currentTimeMillis()
          if (ttlValue < 0) 0l else ttlValue
        })
        .getOrElse(0L)
    }

  override def ttl(key: String): F[Long] =
    pttl(key).map(t => Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): F[Boolean] =
    lift {
      expirations.put(key, System.currentTimeMillis() + (seconds * 1000L))
      true
    }

  override def pexpire(key: String, milliseconds: Long): F[Boolean] =
    lift {
      expirations.put(key, System.currentTimeMillis() + milliseconds)
      true
    }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def getSetAt(key: String): Set[ByteString] =
    getValueAt(key)
      .map { set =>
        set.split(";;;").toSet.map((s: String) => ByteString(s))
      }
      .getOrElse(Set.empty[ByteString])

  private def setSetAt(key: String, set: Set[ByteString]): Unit =
    db.put(bytes(key), bytes(set.map(_.utf8String).mkString(";;;")))

  override def sadd(key: String, members: String*): F[Long] =
    saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): F[Long] =
    lift {
      val seq    = getSetAt(key)
      val newSeq = seq ++ members
      setSetAt(key, newSeq)
      members.size
    }

  override def sismember(key: String, member: String): F[Boolean] =
    sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): F[Boolean] =
    lift {
      val seq = getSetAt(key)
      seq.contains(member)
    }

  override def smembers(key: String): F[Seq[ByteString]] =
    lift {
      val seq = getSetAt(key)
      seq.toSeq
    }

  override def srem(key: String, members: String*): F[Long] =
    sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): F[Long] =
    lift {
      val seq    = getSetAt(key)
      val newSeq = seq.filterNot(b => members.contains(b))
      setSetAt(key, newSeq)
      members.size
    }
//
//  override def zrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
////    get(key).map(maybeData => {
////      case Some(data: JsArray) =>
////        val seq: Seq[ByteString] = data.value.toSeq.map(jsValue => ByteString(Json.fromJson[String](jsValue).get))
////        seq
////      case None =>
////        Seq.empty[ByteString]
////    })
//
//    ???
//  }
//
//  override def zadd(key: String, scoreMembers: (Double, String)*): Future[Long] = {
////    get(key).map(_ => {
////      case Some(data: JsArray) =>
////        set(key, Json.stringify(Json.arr(data.+(scoreMembers.get(0)._2))))
////      case None =>
////        set(key, Json.stringify(Json.arr(Seq(scoreMembers.get(0)._2))))
////    }).map(_ => 0)
//    ???
//  }
}
