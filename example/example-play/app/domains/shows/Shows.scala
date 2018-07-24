package domains.shows

import java.util.Date

import akka.http.scaladsl.util.FastFuture
import cats.implicits._
import env.{BetaSerieConfig, TvdbConfig}
import izanami.scaladsl.FeatureClient
import play.api.Logger
import play.api.libs.json.{JsPath, Json, Reads}
import play.api.libs.ws.{WSClient, WSResponse}

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

class AllShows(tvdbShows: TvdbShows, betaSerieShows: BetaSerieShows, featureClient: FeatureClient)(
    implicit ec: ExecutionContext
) extends Shows[Future] {

  featureClient.onEvent("mytvshows:*") {
    case any => Logger.info(s"New event $any")
  }

  override def get(id: String): Future[Option[Show]] =
    featureClient.features("mytvshows:providers:*").flatMap { features =>
      if (features.isActive("mytvshows:providers:tvdb")) {
        tvdbShows.get(id)
      } else if (features.isActive("mytvshows:providers:betaserie")) {
        betaSerieShows.get(id)
      } else {
        FastFuture.successful(none[Show])
      }
    }

  override def search(show: String): Future[Seq[ShowResume]] =
    featureClient.features("mytvshows:providers:*").flatMap { features =>
      if (features.isActive("mytvshows:providers:tvdb")) {
        tvdbShows.search(show)
      } else if (features.isActive("mytvshows:providers:betaserie")) {
        betaSerieShows.search(show)
      } else {
        FastFuture.successful(Seq.empty)
      }
    }
}

object TvdbShows {

  object TvshowResume {
    implicit val format = Json.format[TvshowResume]
  }
  case class TvshowResume(banner: Option[String],
                          id: Int,
                          imdbId: Option[String],
                          network: String,
                          overview: Option[String],
                          seriesName: String,
                          status: String) {

    def toShow(baseUrl: String, seasons: Seq[Season]): Show =
      Show(id.toString, seriesName, overview.getOrElse(""), banner.map(b => s"$baseUrl/$b"), seasons)

    def toShowResume(baseUrl: String): ShowResume =
      ShowResume(id.toString, seriesName, overview.getOrElse(""), banner.map(b => s"$baseUrl/$b"), "tvdb")
  }

  object PagedResponse {
    import play.api.libs.json._
    implicit def reads[T](implicit reads: Reads[T]): Reads[PagedResponse[T]] =
      (JsPath \ "data").read[Seq[T]].map(s => PagedResponse[T](s))
  }
  case class PagedResponse[T](data: Seq[T])

  object SimpleResponse {
    import play.api.libs.json._
    implicit def reads[T](implicit reads: Reads[T]): Reads[SimpleResponse[T]] =
      (JsPath \ "data").read[T].map(s => SimpleResponse[T](s))
  }
  case class SimpleResponse[T](data: T)

  object EpisodeResume {
    implicit val format = Json.format[EpisodeResume]
  }
  case class EpisodeResume(id: Int, airedEpisodeNumber: Int, airedSeason: Int, episodeName: String, overview: String) {
    def toEpisode: Episode = Episode(id.toString, airedEpisodeNumber, airedSeason, episodeName, overview, false)
  }

  object Login {
    implicit val format = Json.format[Login]
  }
  case class Login(apikey: String)
}

class TvdbShows(config: TvdbConfig, wSClient: WSClient)(implicit ec: ExecutionContext) extends Shows[Future] {

  import TvdbShows._

  val accessHeaders = getAccessHeaders()

  private def getAccessToken(): Future[String] =
    wSClient
      .url(s"${config.url}/login")
      .post(Json.toJson(Login(config.apiKey)))
      .flatMap {
        parseResponse[String]("token") { j =>
          (j \ "token").validate[String]
        }
      }

  private def getAccessHeaders(): Future[Seq[(String, String)]] =
    getAccessToken().map { token =>
      Seq(
        "Authorization" -> s"Bearer $token",
        "Accept"        -> "application/json"
      )
    }

  override def get(id: String): Future[Option[Show]] =
    getTvDbShow(id).flatMap {
      _.map { show =>
        val fSeasons: Future[Seq[Season]] = listTvDbShowsEpisodes(id)
          .map {
            _.map(_.toEpisode)
              .groupBy(_.seasonNumber)
              .map {
                case (nm, eps) => Season(nm.toInt, eps, false)
              }
              .toSeq
          }
        fSeasons.map { s =>
          show.toShow(config.baseUrl, s)
        }
      }.sequence
    }

  override def search(show: String): Future[Seq[ShowResume]] =
    searchTvDbShows(show).map(_.map(_.toShowResume(config.baseUrl)))

  private def searchTvDbShows(text: String): Future[Seq[TvshowResume]] =
    text match {
      case "" => FastFuture.successful(Seq.empty[TvshowResume])
      case _ =>
        accessHeaders.flatMap { headers =>
          wSClient
            .url(s"${config.url}/search/series")
            .addQueryStringParameters("name" -> text)
            .addHttpHeaders(headers: _*)
            .get()
            .flatMap { parseResponse[PagedResponse[TvshowResume]]("search") }
            .map(_.data)
            .recover { case _ => Seq.empty }
        }
    }

  private def getTvDbShow(id: String): Future[Option[TvshowResume]] =
    accessHeaders.flatMap { headers =>
      wSClient
        .url(s"${config.url}/series/$id")
        .addHttpHeaders(headers: _*)
        .get()
        .flatMap {
          case r if r.status === 200 =>
            r.json
              .validate[SimpleResponse[TvshowResume]]
              .fold(
                e => FastFuture.failed(new RuntimeException(s"Error reading response $e")),
                t => FastFuture.successful(t.data.some)
              )
          case r if r.status === 404 =>
            FastFuture.successful(none[TvshowResume])
          case other =>
            FastFuture.failed(new RuntimeException(s"Error while login ${other.statusText} ${other.body}"))
        }
    }

  def listTvDbShowsEpisodes(tvdbId: String): Future[Seq[EpisodeResume]] =
    accessHeaders.flatMap { headers =>
      wSClient
        .url(s"${config.url}/series/$tvdbId/episodes")
        .addHttpHeaders(headers: _*)
        .get()
        .flatMap { parseResponse[PagedResponse[EpisodeResume]]("list") }
        .map(_.data)
    }

  def parseResponse[T](key: String, code: Int = 200)(implicit reads: Reads[T]): WSResponse => Future[T] = {
    case r if r.status === code =>
      r.json
        .validate[T]
        .fold(
          e => FastFuture.failed(new RuntimeException(s"Error reading response $e")),
          t => FastFuture.successful(t)
        )
    case other =>
      FastFuture.failed(new RuntimeException(s"Error ${key} ${other.statusText} ${other.body}"))
  }
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
