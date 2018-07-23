package domains.me

import java.io.File

import cats._
import cats.implicits._
import cats.effect._
import cats.syntax._
import domains.shows.{Season, Show, Shows}
import org.iq80.leveldb.{DB, Options}
import org.iq80.leveldb.impl.Iq80DBFactory.{bytes, factory}
import play.api.libs.json.{Format, Json}

import scala.concurrent.{ExecutionContext, Future}

case class Me(userId: String, shows: Seq[Show] = Seq.empty[Show]) {
  def addShow(mayBeShow: Option[Show]): Me =
    this.copy(
      shows = mayBeShow
        .map { show =>
          shows.map {
            case s if s.id === show.id =>
              show
            case other => other
          }
        }
        .getOrElse(shows)
    )

  def markEpisodeAsWatched(id: String, episodeId: String, watched: Boolean = true): Me =
    this.copy(
      shows = shows.map {
        case s if s.id === id =>
          s.copy(
            seasons = s.seasons.map { season =>
              season.copy(
                episodes = season.episodes.map {
                  case e if e.id === episodeId =>
                    e.copy(watched = watched)
                  case e => e
                }
              )
            }
          )
        case other => other
      }
    )
  def markSeasonAsWatched(id: String, number: Int, watched: Boolean = true): Me =
    this.copy(
      shows = shows.map {
        case s if s.id === id =>
          s.copy(
            seasons = s.seasons.map {
              case season if season.number === number =>
                season.copy(
                  episodes = season.episodes.map { e =>
                    e.copy(watched = watched)
                  },
                  allWatched = watched
                )
              case s => s
            }
          )
        case other => other
      }
    )

  def removeShow(id: String): Me = this.copy(shows = shows.filterNot(_.id === id))
}

trait MeRepository[F[_]] {
  def get(id: String): F[Option[Me]]
  def save(id: String, me: Me): F[Me]
  def delete(id: String): F[Unit]
}

trait MeService[F[_]] {

  def repo: MeRepository[F]
  def shows: Shows[F]

  def addTvShow(userId: String, id: String)(implicit M: Monad[F]): F[Me] =
    for {
      me        <- get(userId)
      mayBeShow <- shows.get(id)
      meUpdated = me.addShow(mayBeShow)
      _         <- repo.save(userId, meUpdated)
    } yield meUpdated

  def markEpisode(userId: String, id: String, episodeId: String, watched: Boolean = true)(implicit M: Monad[F]): F[Me] =
    for {
      me        <- get(userId)
      meUpdated = me.markEpisodeAsWatched(id, episodeId, watched)
      _         <- repo.save(userId, meUpdated)
    } yield meUpdated

  def markSeason(userId: String, id: String, number: Int, watched: Boolean = true)(implicit M: Monad[F]): F[Me] =
    for {
      me        <- get(userId)
      meUpdated = me.markSeasonAsWatched(id, number, watched)
      _         <- repo.save(userId, meUpdated)
    } yield meUpdated

  def removeTvShow(userId: String, tvshowId: String)(implicit M: Monad[F]): F[Me] =
    for {
      me        <- get(userId)
      meUpdated = me.removeShow(tvshowId)
      _         <- repo.save(userId, meUpdated)
    } yield meUpdated

  def get(userId: String)(implicit F: Functor[F]): F[Me] =
    repo.get(userId).map(_.getOrElse(Me(userId)))

}

class LevelDbMeRepository(path: String)(implicit ec: ExecutionContext, format: Format[Me])
    extends MeRepository[Future] {

  private val options = new Options()
  options.createIfMissing(true)
  private val db: DB = factory.open(new File(path), options)

  override def get(id: String): Future[Option[Me]] =
    Future {
      val src     = db.get(bytes(id))
      val maybeMe = Option(src).map(json => format.reads(Json.parse(json)).get)
      maybeMe
    }

  override def save(id: String, me: Me): Future[Me] =
    Future {
      val rawMe = Json.stringify(format.writes(me))
      db.put(bytes(me.userId), bytes(rawMe))
      me
    }

  override def delete(id: String): Future[Unit] =
    Future {
      db.delete(bytes(id))
      ()
    }
}
