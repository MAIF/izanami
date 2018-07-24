package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import controllers.actions.SecuredAction
import domains.me.MeService
import env.Env
import izanami.scaladsl.FeatureClient
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.Future

class MeController(env: Env,
                   meService: MeService[Future],
                   featureClient: FeatureClient,
                   SecuredAction: SecuredAction,
                   cc: ControllerComponents)(
    implicit system: ActorSystem
) extends AbstractController(cc) {

  import cats._
  import cats.implicits._
  import system.dispatcher
  import domains.me.Me._

  def me() = SecuredAction.async { implicit req =>
    meService.get(req.userId).map { me =>
      Ok(Json.toJson(me))
    }
  }

  def addTvShow(id: String) = SecuredAction.async { implicit req =>
    meService.addTvShow(req.userId, id).map { me =>
      Ok(Json.toJson(me))
    }
  }

  def removeTvShow(id: String) = SecuredAction.async { implicit req =>
    meService.removeTvShow(req.userId, id).map { me =>
      Ok(Json.toJson(me))
    }
  }

  def markEpisode(showId: String, episodeId: String, watched: Boolean) = SecuredAction.async { implicit req =>
    meService.markEpisode(req.userId, showId, episodeId, watched).map { me =>
      Ok(Json.toJson(me))
    }
  }

  def markSeason(showId: String, season: Int, watched: Boolean) = SecuredAction.async { implicit req =>
    featureClient.featureOrElseAsync("mytvshows:season:markaswatched") {
      FastFuture.successful(BadRequest(Json.obj()))
    } {
      meService.markSeason(req.userId, showId, season, watched).map { me =>
        Ok(Json.toJson(me))
      }
    }
  }

}
