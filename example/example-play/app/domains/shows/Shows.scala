package domains.shows

import akka.http.scaladsl.util.FastFuture
import cats.implicits._
import env.BetaSerieConfig
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}

object ShowResume {
  implicit val format = Json.format[ShowResume]
}
case class ShowResume(
    id: String,
    title: String,
    description: String,
    image: Option[String],
    source: String
)

object Episode {
  implicit val format = Json.format[Episode]
}
case class Episode(
    id: String,
    number: Long,
    seasonNumber: Long,
    title: String,
    description: String,
    watched: Boolean
)

object Season {
  implicit val format = Json.format[Season]
}
case class Season(number: Int, episodes: Seq[Episode], allWatched: Boolean)

object Show {
  implicit val format = Json.format[Show]
}
case class Show(id: String, title: String, description: String, image: Option[String], seasons: Seq[Season])

trait Shows[F[_]] {
  def get(id: String): F[Option[Show]]

  def search(show: String): F[Seq[ShowResume]]
}

object BetaSerieShows {

  object Images {
    implicit val format = Json.format[Images]
  }
  case class Images(show: Option[String], banner: Option[String], box: Option[String], poster: Option[String])

  object EpisodeResume {
    implicit val format = Json.format[EpisodeResume]
  }
  case class EpisodeResume(id: Long, episode: Long, season: Long, title: String, description: String) {
    def toEpisode = Episode(id.toString, episode, season, title, description, false)
  }

  object BetaSerie {
    implicit val format = Json.format[BetaSerie]
  }
  case class BetaSerie(id: Long,
                       thetvdb_id: Long,
                       imdb_id: String,
                       title: String,
                       description: String,
                       images: Option[Images]) {
    def toShow(season: Seq[Season]): Show = Show(id.toString, title, description, images.flatMap(_.banner), season)
    def toShowResume                      = ShowResume(id.toString, title, description, images.flatMap(_.banner), "betaserie")
  }

  object BetaSerieResume {
    implicit val format = Json.format[BetaSerieResume]
  }
  case class BetaSerieResume(id: Long, thetvdb_id: Option[Long], imdb_id: Option[String], title: String)

  object BetaSerieShowResume {
    implicit val format = Json.format[BetaSerieShowResume]
  }
  case class BetaSerieShowResume(shows: Seq[BetaSerieResume])

  object BetaSerieEpisodes {
    implicit val format = Json.format[BetaSerieEpisodes]
  }
  case class BetaSerieEpisodes(episodes: Seq[EpisodeResume])

  object BetaSerieShow {
    implicit val format = Json.format[BetaSerieShow]
  }
  case class BetaSerieShow(show: BetaSerie)

}

class BetaSerieShows(config: BetaSerieConfig, wSClient: WSClient)(implicit ec: ExecutionContext) extends Shows[Future] {

  import BetaSerieShows._

  override def get(id: String): Future[Option[Show]] =
    for {
      serie        <- getSerie(id)
      betaEpisodes <- getEpisodes(id)
    } yield {
      val episodes = betaEpisodes.map(_.toEpisode)
      val seasons = episodes.groupBy(_.seasonNumber).map {
        case (n, eps) => Season(n.toInt, eps, false)
      }
      serie.map(_.toShow(seasons.toSeq))
    }

  override def search(show: String): Future[Seq[ShowResume]] = {
    import cats._
    import cats.implicits._
    searchSerie(show).flatMap { shows =>
      val sequence: Future[List[BetaSerie]] =
        shows.toList.traverse[Future, Option[BetaSerie]](s => getSerie(s.id.toString)).map(_.flatten)
      val series: Future[Seq[ShowResume]] = sequence.map(_.map(_.toShowResume))
      series
    }
  }

  def searchSerie(text: String): Future[Seq[BetaSerieResume]] =
    text match {
      case "" =>
        FastFuture.successful(Seq.empty[BetaSerieResume])
      case _ =>
        wSClient
          .url(s"${config.url}/search/all")
          .addQueryStringParameters(
            "query" -> text,
            "key"   -> config.apiKey
          )
          .get()
          .flatMap {
            case r if r.status === 200 =>
              val show = r.json
                .validate[BetaSerieShowResume]
                .fold(
                  e => {
                    Logger.error(s"Deserialization error for \n${r.json} \n Error: $e")
                    None
                  },
                  s => s.some
                )
              FastFuture.successful(show.get.shows)
            case r =>
              FastFuture.failed(new RuntimeException(s"Error getting betaseries show ${r.body}"))

          }
    }

  def getSerie(id: String): Future[Option[BetaSerie]] =
    wSClient
      .url(s"${config.url}/shows/display")
      .addQueryStringParameters(
        "id"  -> id,
        "key" -> config.apiKey
      )
      .get()
      .flatMap {
        case r if r.status === 200 =>
          val show = r.json
            .validate[BetaSerieShow]
            .fold(
              e => {
                Logger.error(s"Deserialization error for \n${r.json} \n Error: $e")
                None
              },
              s => s.some
            )
            .get
          FastFuture.successful(show.show.some)
        case r =>
          FastFuture.failed(new RuntimeException(s"Error getting betaseries show ${r.body}"))

      }

  def getEpisodes(id: String): Future[Seq[EpisodeResume]] =
    wSClient
      .url(s"${config.url}/shows/episodes")
      .addQueryStringParameters(
        "id"  -> id,
        "key" -> config.apiKey
      )
      .get()
      .flatMap {
        case r if r.status === 200 =>
          val episodes = r.json.validate[BetaSerieEpisodes].get
          FastFuture.successful(episodes.episodes)
        case r =>
          FastFuture.failed(new RuntimeException(s"Error getting betaseries show ${r.body}"))

      }
}
